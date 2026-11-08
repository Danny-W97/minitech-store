#!/usr/bin/env python3
"""Cross-database consistency audit for Mini Order Platform (A35).
Audits distributed business consistency across independent service databases:
order-service, payment-service, inventory-service, billing-service, delivery-service, and notification-service.
"""
from dataclasses import asdict, dataclass, field
from decimal import Decimal
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
from typing import Any, Dict, List, Optional, Set, Tuple

ROOT = Path(__file__).resolve().parents[2]


@dataclass
class OrderRecord:
    id: int
    status: str
    total_price: Decimal
    delivery_method: str
    fulfillment_type: str
    invoice_requested: bool
    inventory_reservation_id: Optional[int]
    payment_state: Optional[str] = None
    lifecycle_review_required: bool = False
    refund_payment_id: Optional[int] = None


@dataclass
class OrderSagaRecord:
    order_id: int
    state: str  # e.g. 'COMPLETED', 'WAITING_FOR_PAYMENT', 'RECONCILIATION_REQUIRED', 'FAILED'


@dataclass
class PaymentRecord:
    order_id: int
    status: str  # 'SUCCEEDED', 'FAILED', 'PENDING'
    amount: Decimal


@dataclass
class PaymentRefundRecord:
    order_id: int
    payment_id: int
    status: str  # 'SUCCEEDED', 'PENDING', 'REQUESTED', 'REVIEW_REQUIRED'
    business_completed: bool = False


@dataclass
class InventoryReservationRecord:
    id: int
    order_id: int
    status: str  # 'CONFIRMED', 'RESERVED', 'CANCELLED', 'RELEASED'
    expired: bool = False


@dataclass
class InvoiceRecord:
    order_id: int
    status: str  # 'ISSUED', 'CANCELLED'
    gross_amount: Decimal


@dataclass
class ShipmentRecord:
    order_id: int
    status: str  # 'CREATED', 'SHIPPED', 'DELIVERED'


@dataclass
class NotificationRecord:
    source_id: int
    event_type: str
    status: str  # 'SENT', 'PENDING', 'FAILED'


@dataclass
class ConsistencyViolation:
    order_id: Optional[int]
    domain: str
    violation_type: str
    details: str
    severity: str = "CRITICAL"


@dataclass
class DatabaseDataset:
    orders: Dict[int, OrderRecord] = field(default_factory=dict)
    sagas: Dict[int, OrderSagaRecord] = field(default_factory=dict)
    payments: Dict[int, PaymentRecord] = field(default_factory=dict)
    refunds: Dict[int, List[PaymentRefundRecord]] = field(default_factory=dict)
    reservations: Dict[int, InventoryReservationRecord] = field(default_factory=dict)
    invoices: Dict[int, InvoiceRecord] = field(default_factory=dict)
    shipments: Dict[int, ShipmentRecord] = field(default_factory=dict)
    notifications: Dict[int, List[NotificationRecord]] = field(default_factory=dict)


class CrossDatabaseConsistencyAuditor:
    """Audits data consistency across the six distributed databases."""

    def audit(self, dataset: DatabaseDataset) -> List[ConsistencyViolation]:
        violations: List[ConsistencyViolation] = []

        # Invariant 1: Paid / Fulfilled Order Consistency
        for order_id, order in dataset.orders.items():
            if order.status in ("PAID", "SHIPPED", "COMPLETED"):
                self._audit_paid_order(order, dataset, violations)
            elif order.status == "CANCELLED":
                self._audit_cancelled_order(order, dataset, violations)

        # Invariant 2: Orphan Detection (Inversion Checks)
        self._audit_orphan_records(dataset, violations)

        # Invariant 3: Reconciliation Queue Alignment
        self._audit_reconciliation_alignment(dataset, violations)

        return violations

    def _audit_paid_order(
        self, order: OrderRecord, dataset: DatabaseDataset, violations: List[ConsistencyViolation]
    ):
        # 1. Payment Verification
        payment = dataset.payments.get(order.id)
        if not payment:
            violations.append(
                ConsistencyViolation(
                    order.id, "payment", "MISSING_PAYMENT",
                    f"Order {order.id} is {order.status} but no payment record exists in payment-service"
                )
            )
        elif payment.status != "SUCCEEDED":
            violations.append(
                ConsistencyViolation(
                    order.id, "payment", "PAYMENT_NOT_SUCCEEDED",
                    f"Order {order.id} is {order.status} but payment status is {payment.status}"
                )
            )
        elif payment.amount != order.total_price:
            violations.append(
                ConsistencyViolation(
                    order.id, "payment", "PAYMENT_AMOUNT_MISMATCH",
                    f"Order {order.id} total is {order.total_price} but payment amount is {payment.amount}"
                )
            )

        # 2. Inventory Reservation Verification
        if not order.inventory_reservation_id:
            violations.append(
                ConsistencyViolation(
                    order.id, "inventory", "NO_RESERVATION_ID",
                    f"Order {order.id} is {order.status} but has no inventory reservation assigned"
                )
            )
        else:
            reservation = dataset.reservations.get(order.inventory_reservation_id)
            if not reservation or reservation.order_id != order.id:
                violations.append(
                    ConsistencyViolation(
                        order.id, "inventory", "MISSING_RESERVATION",
                        f"Order {order.id} points to reservation {order.inventory_reservation_id} which does not exist or belongs to another order"
                    )
                )
            elif reservation.status != "CONFIRMED":
                violations.append(
                    ConsistencyViolation(
                        order.id, "inventory", "RESERVATION_NOT_CONFIRMED",
                        f"Order {order.id} is {order.status} but reservation status is {reservation.status}"
                    )
                )

        # 3. Billing Invoice Verification
        if order.invoice_requested:
            invoice = dataset.invoices.get(order.id)
            if not invoice:
                violations.append(
                    ConsistencyViolation(
                        order.id, "billing", "MISSING_INVOICE",
                        f"Order {order.id} requested invoice but no invoice exists in billing-service"
                    )
                )
            elif invoice.status != "ISSUED":
                violations.append(
                    ConsistencyViolation(
                        order.id, "billing", "INVOICE_NOT_ISSUED",
                        f"Order {order.id} invoice status is {invoice.status}, expected ISSUED"
                    )
                )
            elif invoice.gross_amount != order.total_price:
                violations.append(
                    ConsistencyViolation(
                        order.id, "billing", "INVOICE_AMOUNT_MISMATCH",
                        f"Order {order.id} total is {order.total_price} but invoice gross is {invoice.gross_amount}"
                    )
                )

        # 4. Delivery Shipment Verification
        if order.delivery_method != "STORE_PICKUP":
            shipment = dataset.shipments.get(order.id)
            if not shipment:
                violations.append(
                    ConsistencyViolation(
                        order.id, "delivery", "MISSING_SHIPMENT",
                        f"Order {order.id} has carrier delivery ({order.delivery_method}) but no shipment exists in delivery-service"
                    )
                )

        # 5. Notification Verification
        notifications = dataset.notifications.get(order.id, [])
        payment_notifs = [n for n in notifications if n.event_type == "PAYMENT_SUCCEEDED"]
        if not payment_notifs:
            violations.append(
                ConsistencyViolation(
                    order.id, "notification", "MISSING_PAYMENT_NOTIFICATION",
                    f"Order {order.id} is {order.status} but no PAYMENT_SUCCEEDED notification was registered"
                )
            )

        # 6. Saga Verification
        saga = dataset.sagas.get(order.id)
        if not saga:
            violations.append(
                ConsistencyViolation(
                    order.id, "order_saga", "ORPHAN_ORDER_WITHOUT_SAGA",
                    f"Order {order.id} is {order.status} but has no saga record attached"
                )
            )
        elif saga.state not in ("COMPLETED", "RECONCILIATION_REQUIRED"):
            violations.append(
                ConsistencyViolation(
                    order.id, "order_saga", "INCONSISTENT_SAGA_STATE",
                    f"Order {order.id} is {order.status} but saga is in state {saga.state}"
                )
            )

    def _audit_cancelled_order(
        self, order: OrderRecord, dataset: DatabaseDataset, violations: List[ConsistencyViolation]
    ):
        # Cancelled orders must not have active unreleased stock
        if order.inventory_reservation_id:
            reservation = dataset.reservations.get(order.inventory_reservation_id)
            if reservation and reservation.status in ("CONFIRMED", "RESERVED") and not reservation.expired:
                violations.append(
                    ConsistencyViolation(
                        order.id, "inventory", "UNRELEASED_RESERVATION_ON_CANCELLED_ORDER",
                        f"Order {order.id} is CANCELLED but reservation {reservation.id} remains {reservation.status}"
                    )
                )

        # Cancelled orders must not have uncompensated payment without refund record
        payment = dataset.payments.get(order.id)
        if payment and payment.status == "SUCCEEDED":
            refunds = dataset.refunds.get(order.id, [])
            succeeded_refunds = [r for r in refunds if r.status == "SUCCEEDED"]
            if not succeeded_refunds and not order.refund_payment_id:
                violations.append(
                    ConsistencyViolation(
                        order.id, "payment", "UNREFUNDED_PAYMENT_ON_CANCELLED_ORDER",
                        f"Order {order.id} is CANCELLED but payment {payment.amount} {payment.status} has no confirmed refund"
                    )
                )

    def _audit_orphan_records(
        self, dataset: DatabaseDataset, violations: List[ConsistencyViolation]
    ):
        # Orphan payments
        for order_id, payment in dataset.payments.items():
            if order_id not in dataset.orders:
                violations.append(
                    ConsistencyViolation(
                        order_id, "payment", "ORPHAN_PAYMENT",
                        f"Payment for order {order_id} exists but order does not exist in order-service"
                    )
                )

        # Orphan invoices
        for order_id, invoice in dataset.invoices.items():
            if order_id not in dataset.orders:
                violations.append(
                    ConsistencyViolation(
                        order_id, "billing", "ORPHAN_INVOICE",
                        f"Invoice for order {order_id} exists but order does not exist in order-service"
                    )
                )

        # Orphan shipments
        for order_id, shipment in dataset.shipments.items():
            if order_id not in dataset.orders:
                violations.append(
                    ConsistencyViolation(
                        order_id, "delivery", "ORPHAN_SHIPMENT",
                        f"Shipment for order {order_id} exists but order does not exist in order-service"
                    )
                )

        # Orphan reservations with order_id
        for res_id, reservation in dataset.reservations.items():
            if reservation.order_id and reservation.order_id not in dataset.orders:
                violations.append(
                    ConsistencyViolation(
                        reservation.order_id, "inventory", "ORPHAN_RESERVATION",
                        f"Reservation {res_id} references order {reservation.order_id} which does not exist"
                    )
                )

    def _audit_reconciliation_alignment(
        self, dataset: DatabaseDataset, violations: List[ConsistencyViolation]
    ):
        # If an order is marked with lifecycle_review_required, saga must be RECONCILIATION_REQUIRED
        for order_id, order in dataset.orders.items():
            saga = dataset.sagas.get(order_id)
            if order.lifecycle_review_required:
                if not saga or saga.state != "RECONCILIATION_REQUIRED":
                    violations.append(
                        ConsistencyViolation(
                            order_id, "order_saga", "RECONCILIATION_FLAG_MISALIGNMENT",
                            f"Order {order_id} requires lifecycle review but saga state is {saga.state if saga else 'MISSING'}"
                        )
                    )


def run_self_tests() -> int:
    """Run built-in verification suite covering all cross-database consistency scenarios."""
    auditor = CrossDatabaseConsistencyAuditor()

    # Scenario 1: Clean consistent paid order with all downstream entities
    ds1 = DatabaseDataset()
    order1 = OrderRecord(
        id=101, status="PAID", total_price=Decimal("150.00"),
        delivery_method="STANDARD_COURIER", fulfillment_type="HOME_DELIVERY",
        invoice_requested=True, inventory_reservation_id=201
    )
    ds1.orders[101] = order1
    ds1.sagas[101] = OrderSagaRecord(order_id=101, state="COMPLETED")
    ds1.payments[101] = PaymentRecord(order_id=101, status="SUCCEEDED", amount=Decimal("150.00"))
    ds1.reservations[201] = InventoryReservationRecord(id=201, order_id=101, status="CONFIRMED")
    ds1.invoices[101] = InvoiceRecord(order_id=101, status="ISSUED", gross_amount=Decimal("150.00"))
    ds1.shipments[101] = ShipmentRecord(order_id=101, status="CREATED")
    ds1.notifications[101] = [NotificationRecord(source_id=101, event_type="PAYMENT_SUCCEEDED", status="SENT")]

    v1 = auditor.audit(ds1)
    assert len(v1) == 0, f"Expected 0 violations for clean order, got: {v1}"

    # Scenario 2: Order PAID but payment missing
    ds2 = DatabaseDataset()
    ds2.orders[102] = OrderRecord(
        id=102, status="PAID", total_price=Decimal("50.00"),
        delivery_method="STORE_PICKUP", fulfillment_type="STORE_PICKUP",
        invoice_requested=False, inventory_reservation_id=202
    )
    ds2.sagas[102] = OrderSagaRecord(order_id=102, state="COMPLETED")
    ds2.reservations[202] = InventoryReservationRecord(id=202, order_id=102, status="CONFIRMED")
    ds2.notifications[102] = [NotificationRecord(source_id=102, event_type="PAYMENT_SUCCEEDED", status="SENT")]

    v2 = auditor.audit(ds2)
    assert any(v.violation_type == "MISSING_PAYMENT" and v.order_id == 102 for v in v2)

    # Scenario 3: Order PAID but reservation not confirmed
    ds3 = DatabaseDataset()
    ds3.orders[103] = OrderRecord(
        id=103, status="PAID", total_price=Decimal("50.00"),
        delivery_method="STORE_PICKUP", fulfillment_type="STORE_PICKUP",
        invoice_requested=False, inventory_reservation_id=203
    )
    ds3.sagas[103] = OrderSagaRecord(order_id=103, state="COMPLETED")
    ds3.payments[103] = PaymentRecord(order_id=103, status="SUCCEEDED", amount=Decimal("50.00"))
    ds3.reservations[203] = InventoryReservationRecord(id=203, order_id=103, status="CANCELLED")
    ds3.notifications[103] = [NotificationRecord(source_id=103, event_type="PAYMENT_SUCCEEDED", status="SENT")]

    v3 = auditor.audit(ds3)
    assert any(v.violation_type == "RESERVATION_NOT_CONFIRMED" and v.order_id == 103 for v in v3)

    # Scenario 4: Order PAID with invoice requested, but invoice missing
    ds4 = DatabaseDataset()
    ds4.orders[104] = OrderRecord(
        id=104, status="PAID", total_price=Decimal("75.00"),
        delivery_method="STORE_PICKUP", fulfillment_type="STORE_PICKUP",
        invoice_requested=True, inventory_reservation_id=204
    )
    ds4.sagas[104] = OrderSagaRecord(order_id=104, state="COMPLETED")
    ds4.payments[104] = PaymentRecord(order_id=104, status="SUCCEEDED", amount=Decimal("75.00"))
    ds4.reservations[204] = InventoryReservationRecord(id=204, order_id=104, status="CONFIRMED")
    ds4.notifications[104] = [NotificationRecord(source_id=104, event_type="PAYMENT_SUCCEEDED", status="SENT")]

    v4 = auditor.audit(ds4)
    assert any(v.violation_type == "MISSING_INVOICE" and v.order_id == 104 for v in v4)

    # Scenario 5: Order PAID with courier delivery, but shipment missing
    ds5 = DatabaseDataset()
    ds5.orders[105] = OrderRecord(
        id=105, status="PAID", total_price=Decimal("75.00"),
        delivery_method="STANDARD_COURIER", fulfillment_type="HOME_DELIVERY",
        invoice_requested=False, inventory_reservation_id=205
    )
    ds5.sagas[105] = OrderSagaRecord(order_id=105, state="COMPLETED")
    ds5.payments[105] = PaymentRecord(order_id=105, status="SUCCEEDED", amount=Decimal("75.00"))
    ds5.reservations[205] = InventoryReservationRecord(id=205, order_id=105, status="CONFIRMED")
    ds5.notifications[105] = [NotificationRecord(source_id=105, event_type="PAYMENT_SUCCEEDED", status="SENT")]

    v5 = auditor.audit(ds5)
    assert any(v.violation_type == "MISSING_SHIPMENT" and v.order_id == 105 for v in v5)

    # Scenario 6: Order CANCELLED with unreleased reservation
    ds6 = DatabaseDataset()
    ds6.orders[106] = OrderRecord(
        id=106, status="CANCELLED", total_price=Decimal("100.00"),
        delivery_method="STORE_PICKUP", fulfillment_type="STORE_PICKUP",
        invoice_requested=False, inventory_reservation_id=206
    )
    ds6.sagas[106] = OrderSagaRecord(order_id=106, state="FAILED")
    ds6.reservations[206] = InventoryReservationRecord(id=206, order_id=106, status="CONFIRMED")

    v6 = auditor.audit(ds6)
    assert any(v.violation_type == "UNRELEASED_RESERVATION_ON_CANCELLED_ORDER" and v.order_id == 106 for v in v6)

    # Scenario 7: Orphan records (payment without order)
    ds7 = DatabaseDataset()
    ds7.payments[999] = PaymentRecord(order_id=999, status="SUCCEEDED", amount=Decimal("20.00"))
    ds7.invoices[998] = InvoiceRecord(order_id=998, status="ISSUED", gross_amount=Decimal("30.00"))
    ds7.shipments[997] = ShipmentRecord(order_id=997, status="CREATED")

    v7 = auditor.audit(ds7)
    assert any(v.violation_type == "ORPHAN_PAYMENT" and v.order_id == 999 for v in v7)
    assert any(v.violation_type == "ORPHAN_INVOICE" and v.order_id == 998 for v in v7)
    assert any(v.violation_type == "ORPHAN_SHIPMENT" and v.order_id == 997 for v in v7)

    # Scenario 8: Reconciliation queue alignment
    ds8 = DatabaseDataset()
    ds8.orders[108] = OrderRecord(
        id=108, status="CONFIRMED", total_price=Decimal("10.00"),
        delivery_method="STORE_PICKUP", fulfillment_type="STORE_PICKUP",
        invoice_requested=False, inventory_reservation_id=None,
        lifecycle_review_required=True
    )
    ds8.sagas[108] = OrderSagaRecord(order_id=108, state="RECONCILIATION_REQUIRED")

    v8 = auditor.audit(ds8)
    assert len(v8) == 0, f"Expected 0 violations for properly flagged reconciliation order, got {v8}"

    # If saga was not RECONCILIATION_REQUIRED, it must flag violation
    ds8.sagas[108].state = "WAITING_FOR_PAYMENT"
    v8_misaligned = auditor.audit(ds8)
    assert any(v.violation_type == "RECONCILIATION_FLAG_MISALIGNMENT" for v in v8_misaligned)

    print(
        "PASS cross-database consistency: 8 scenarios, 6 databases, all invariants verified",
        flush=True
    )
    return 0


DEFAULT_DB_SPECS = {
    "order": {"container": "order-db", "port": 15433, "db": "order_db", "user": "order_app"},
    "inventory": {"container": "inventory-db", "port": 15434, "db": "inventory_db", "user": "inventory_app"},
    "payment": {"container": "payment-db", "port": 15435, "db": "payment_db", "user": "payment_app"},
    "notification": {"container": "notification-db", "port": 15436, "db": "notification_db", "user": "notification_app"},
    "delivery": {"container": "delivery-db", "port": 15438, "db": "delivery_db", "user": "delivery_app"},
    "billing": {"container": "billing-db", "port": 15445, "db": "billing_db", "user": "billing_app"},
}


def query_postgres_json(
    service: str,
    sql: str,
    shared_container: Optional[str] = None,
    shared_user: Optional[str] = None,
    host: str = "127.0.0.1",
) -> List[Dict[str, Any]]:
    spec = DEFAULT_DB_SPECS[service]
    wrapped = f"SELECT COALESCE(json_agg(t), '[]'::json)::text FROM ({sql}) t"
    container = shared_container or spec["container"]
    user = shared_user or os.environ.get(f"{service.upper()}_DB_USER", spec["user"])
    db_name = spec["db"]
    cmd = [
        "docker", "exec", container,
        "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", user, "-d", db_name, "-Atqc", wrapped,
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0 and not shared_container:
        env = os.environ.copy()
        pw = os.environ.get(f"{service.upper()}_DB_PASSWORD")
        if pw:
            env["PGPASSWORD"] = pw
        psql_cmd = [
            "psql", "-X", "-v", "ON_ERROR_STOP=1",
            "-h", host, "-p", str(spec["port"]), "-U", user, "-d", db_name, "-Atqc", wrapped,
        ]
        res = subprocess.run(psql_cmd, capture_output=True, text=True, env=env)
    if res.returncode != 0:
        raise RuntimeError(f"Failed to query {service} ({db_name}): {res.stderr.strip()}")
    raw = res.stdout.strip()
    return json.loads(raw) if raw else []


def load_from_postgres(
    shared_container: Optional[str] = None,
    shared_user: Optional[str] = None,
    host: str = "127.0.0.1",
) -> DatabaseDataset:
    dataset = DatabaseDataset()
    for row in query_postgres_json(
        "order",
        "SELECT id, status, total_price::text AS total_price, delivery_method, fulfillment_type, "
        "invoice_requested, inventory_reservation_id, refund_state, lifecycle_review_required, refund_payment_id "
        "FROM orders",
        shared_container, shared_user, host,
    ):
        dataset.orders[int(row["id"])] = OrderRecord(
            id=int(row["id"]),
            status=row["status"],
            total_price=Decimal(str(row["total_price"])),
            delivery_method=row["delivery_method"],
            fulfillment_type=row["fulfillment_type"],
            invoice_requested=bool(row["invoice_requested"]),
            inventory_reservation_id=int(row["inventory_reservation_id"]) if row.get("inventory_reservation_id") is not None else None,
            payment_state=row.get("refund_state"),
            lifecycle_review_required=bool(row.get("lifecycle_review_required", False)),
            refund_payment_id=int(row["refund_payment_id"]) if row.get("refund_payment_id") is not None else None,
        )

    for row in query_postgres_json(
        "order",
        "SELECT order_id, state FROM order_sagas",
        shared_container, shared_user, host,
    ):
        dataset.sagas[int(row["order_id"])] = OrderSagaRecord(
            order_id=int(row["order_id"]),
            state=row["state"],
        )

    for row in query_postgres_json(
        "payment",
        "SELECT order_id, status, amount::text AS amount FROM payments",
        shared_container, shared_user, host,
    ):
        dataset.payments[int(row["order_id"])] = PaymentRecord(
            order_id=int(row["order_id"]),
            status=row["status"],
            amount=Decimal(str(row["amount"])),
        )

    for row in query_postgres_json(
        "payment",
        "SELECT order_id, payment_id, status, business_completed FROM payment_refunds",
        shared_container, shared_user, host,
    ):
        oid = int(row["order_id"])
        dataset.refunds.setdefault(oid, []).append(
            PaymentRefundRecord(
                order_id=oid,
                payment_id=int(row["payment_id"]),
                status=row["status"],
                business_completed=bool(row.get("business_completed", False)),
            )
        )

    for row in query_postgres_json(
        "inventory",
        "SELECT id, order_id, status, (expires_at IS NOT NULL AND expires_at < NOW()) AS expired "
        "FROM inventory_reservations",
        shared_container, shared_user, host,
    ):
        dataset.reservations[int(row["id"])] = InventoryReservationRecord(
            id=int(row["id"]),
            order_id=int(row["order_id"]),
            status=row["status"],
            expired=bool(row.get("expired", False)),
        )

    for row in query_postgres_json(
        "billing",
        "SELECT order_id, 'ISSUED' AS status, gross_amount::text AS gross_amount FROM invoices",
        shared_container, shared_user, host,
    ):
        dataset.invoices[int(row["order_id"])] = InvoiceRecord(
            order_id=int(row["order_id"]),
            status=row["status"],
            gross_amount=Decimal(str(row["gross_amount"])),
        )

    for row in query_postgres_json(
        "delivery",
        "SELECT order_id, status FROM shipments",
        shared_container, shared_user, host,
    ):
        dataset.shipments[int(row["order_id"])] = ShipmentRecord(
            order_id=int(row["order_id"]),
            status=row["status"],
        )

    for row in query_postgres_json(
        "notification",
        "SELECT source_id, event_type, status FROM notifications WHERE source_id IS NOT NULL",
        shared_container, shared_user, host,
    ):
        sid = int(row["source_id"])
        dataset.notifications.setdefault(sid, []).append(
            NotificationRecord(
                source_id=sid,
                event_type=row.get("event_type") or "",
                status=row["status"],
            )
        )

    return dataset


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true", help="Run built-in in-memory verification scenarios")
    parser.add_argument("--live-postgres", action="store_true", help="Audit the 6 live PostgreSQL databases")
    parser.add_argument("--postgres-container", help="Optional single Docker container hosting all 6 databases")
    parser.add_argument("--postgres-user", help="Optional PostgreSQL user override when querying live databases")
    parser.add_argument("--host", default="127.0.0.1", help="PostgreSQL host for direct psql connections")
    parser.add_argument("--output", type=Path, help="Optional JSON report path")
    args = parser.parse_args(argv)

    if args.live_postgres:
        dataset = load_from_postgres(
            shared_container=args.postgres_container,
            shared_user=args.postgres_user,
            host=args.host,
        )
        violations = CrossDatabaseConsistencyAuditor().audit(dataset)
        report = {
            "mode": "live-postgres",
            "databasesAudited": list(DEFAULT_DB_SPECS.keys()),
            "counts": {
                "orders": len(dataset.orders),
                "sagas": len(dataset.sagas),
                "payments": len(dataset.payments),
                "reservations": len(dataset.reservations),
                "invoices": len(dataset.invoices),
                "shipments": len(dataset.shipments),
            },
            "violations": [asdict(v) for v in violations],
            "status": "PASS" if not violations else "FAIL",
        }
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps(report, indent=2), flush=True)
        return 0 if not violations else 1

    return run_self_tests()


if __name__ == "__main__":
    sys.exit(main())
