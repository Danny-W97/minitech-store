"""Control Center feature; runtime dependencies are supplied by the composition root."""
from __future__ import annotations
from typing import Any
from email.mime.application import MIMEApplication
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
import html
import json
import re
import smtplib
import time
import urllib.request
import uuid
import cc_dispatch_history

def get_orders_data(services, params: dict) -> dict[str, Any]:
    page = services.api_page(f"{services.GATEWAY_API}/orders", params)
    orders = page.pop("items")
    ids = [int(o["id"]) for o in orders]
    invoices = services.psql_query_json("billing", "SELECT order_id, invoice_number FROM invoices WHERE order_id IN (" + ",".join(map(str, ids)) + ")") if ids else []
    numbers = {row["order_id"]: row["invoice_number"] for row in invoices}
    for order in orders:
        order["invoiceNumber"] = numbers.get(order["id"])
        order["hasInvoice"] = bool(order["invoiceNumber"])
    return {"orders": orders, **page}

def generate_invoice_for_order(services, order_id: int) -> dict[str, Any]:
    try:
        order = services.api_json(f"{services.GATEWAY_API}/orders/{order_id}", timeout=3.0)
    except Exception as exc:
        return {"success": False, "error": f"Nie można zweryfikować zamówienia #{order_id}: {exc}"}
    if not isinstance(order, dict) or not order.get("id"):
        return {"success": False, "error": f"Nie znaleziono zamówienia #{order_id}."}
    if order.get("lifecycleReviewRequired") or order.get("paymentState", "PAID") != "PAID" or order.get("status") not in {"PAID", "SHIPPED", "COMPLETED"}:
        return {"success": False, "error": "Faktura wymaga opłaconego zamówienia."}

    raw_items = order.get("items") or []
    if not raw_items or not order.get("customerEmail"):
        return {"success": False, "error": "Zamówienie nie zawiera pozycji lub adresu e-mail do wystawienia faktury."}
    items_payload = [{key: item.get(key) for key in
                      ("productId", "sku", "productName", "quantity", "unitPrice", "totalPrice")}
                     for item in raw_items]

    addr = order.get("deliveryAddress") or {}
    first_name = addr.get("firstName") or order.get("delivery_first_name") or ""
    last_name = addr.get("lastName") or order.get("delivery_last_name") or ""
    buyer_name = f"{first_name} {last_name}".strip() or "Klient Indywidualny"
    street = addr.get("street") or order.get("delivery_street") or "ul. Sklepowa 1"
    postal_code = addr.get("postalCode") or order.get("delivery_postal_code") or "00-001"
    city = addr.get("city") or order.get("delivery_city") or "Warszawa"
    country = addr.get("country") or order.get("delivery_country") or "Polska"
    invoice_payload = {
        "orderId": order_id,
        "customerId": order.get("customerId"),
        "customerEmail": order["customerEmail"],
        "grossAmount": order.get("totalPrice"),
        "currency": "PLN",
        "deliveryCost": order.get("deliveryCost"),
        "discountAmount": order.get("discountAmount"),
        "items": items_payload,
        "billingData": {
            "company": False,
            "buyerName": buyer_name,
            "companyName": None,
            "taxId": None,
            "street": street,
            "postalCode": postal_code,
            "city": city,
            "country": country,
        },
    }

    try:
        body_bytes = json.dumps(invoice_payload).encode("utf-8")
        req = services.platform_request(
            f"{services.GATEWAY_API}/invoices",
            data=body_bytes,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=5.0) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return {"success": True, "invoice": data, "invoiceNumber": data.get("invoiceNumber")}
    except urllib.error.HTTPError as err:
        return {"success": False, "error": f"Billing Service odrzucił wystawienie faktury (HTTP {err.code})."}
    except Exception as exc:
        return {"success": False, "error": f"Błąd wystawiania faktury: {exc}"}

def get_corrections_data(services, invoice_id: int, params: dict) -> dict[str, Any]:
    return services.api_page(f"{services.GATEWAY_API}/invoices/{invoice_id}/corrections", params)

def get_dispatch_corrections(services, params: dict) -> dict[str, Any]:
    page = services.api_page(f"{services.GATEWAY_API}/invoices/corrections", params)
    ids = [int(c["invoiceId"]) for c in page["items"]]
    invoices = services.psql_query_json("billing", "SELECT id, customer_email, buyer_name, company_name, currency FROM invoices WHERE id IN (" + ",".join(map(str, ids)) + ")") if ids else []
    by_id = {i["id"]: i for i in invoices}
    for correction in page["items"]:
        info = by_id.get(correction["invoiceId"], {})
        correction.update({"customerEmail": info.get("customer_email", ""), "buyerName": info.get("buyer_name", ""), "companyName": info.get("company_name", ""), "currency": info.get("currency", "PLN")})
    return page

def get_invoices_data(services, params: dict) -> dict[str, Any]:
    page = services.api_page(f"{services.GATEWAY_API}/invoices", params)
    invoices = page.pop("items")
    totals = services.api_json(f"{services.GATEWAY_API}/invoices/corrections/summary?" + urllib.parse.urlencode({"invoiceIds": ",".join(str(i["id"]) for i in invoices)})) if invoices else []
    by_id = {row["invoiceId"]: row for row in totals}
    for invoice in invoices:
        summary = by_id.get(invoice["id"], {})
        invoice["correctionCount"] = summary.get("correctionCount", 0)
        invoice["correctionGrossDelta"] = summary.get("grossDelta", 0)
        invoice["corrections"] = []
    return {"invoices": invoices, **page}

def load_dispatch_history(services) -> list[dict[str, Any]]:
    return cc_dispatch_history.load(services.RUNTIME_ROOT,services.DISPATCH_HISTORY_FILE)

def record_dispatch(services, entry: dict[str, Any]) -> bool:
    try:
        cc_dispatch_history.append(services.RUNTIME_ROOT,entry)
        return True
    except Exception:
        return False

def send_document_email(services, 
    doc_type: str,
    doc_id: Any,
    order_id: Any,
    doc_number: str,
    recipient: str,
    subject: str = "",
    custom_message: str = "",
    buyer_name: str = "",
) -> dict[str, Any]:
    try:
        if doc_type not in ('INVOICE','CORRECTION') or not re.fullmatch('[1-9][0-9]{0,18}',str(doc_id)) or not re.fullmatch('[1-9][0-9]{0,18}',str(order_id)):raise ValueError()
        doc_id,order_id=int(doc_id),int(order_id)
        if max(doc_id,order_id)>2**63-1:raise ValueError()
    except (ValueError,TypeError):return {'success':False,'error':'Wymagane prawidłowe ID dokumentu i zamówienia.'}
    # 1. Fetch PDF content
    pdf_bytes: bytes | None = None
    is_corr = str(doc_type).upper() == "CORRECTION"
    if is_corr:
        candidates = [
            f"{services.GATEWAY_API}/invoices/corrections/{doc_id}/pdf",
            f"{services.BILLING_DIRECT_API}/invoices/corrections/{doc_id}/pdf",
        ]
    else:
        candidates = [
            f"{services.GATEWAY_API}/invoices/{doc_id}/pdf",
            f"{services.BILLING_DIRECT_API}/invoices/{doc_id}/pdf",
            f"{services.GATEWAY_API}/invoices/order/{order_id}/pdf",
            f"{services.BILLING_DIRECT_API}/invoices/order/{order_id}/pdf",
        ]

    for u in candidates:
        try:
            req = services.platform_request(u)
            with urllib.request.urlopen(req, timeout=10.0) as resp:
                data = resp.read()
                if data and len(data) > 100:
                    pdf_bytes = data
                    break
        except Exception:
            continue

    if not pdf_bytes:
        return {"success": False, "error": f"Nie udało się pobrać pliku PDF dokumentu {doc_number} z Billing Service."}

    # 2. Build email
    doc_title = "Faktura Korygująca (KOR)" if is_corr else "Faktura VAT"
    if not subject:
        subject = f"[MiniTech] {doc_title} nr {doc_number} do zamówienia #{order_id}"

    msg = MIMEMultipart("alternative")
    msg["Subject"] = subject
    msg["From"] = "MiniTech Store <faktury@minitech.local>"
    msg["To"] = recipient

    safe_nr = re.sub(r"[^\w\-_.]", "_", doc_number)
    file_prefix = "korekta" if is_corr else "faktura"
    attachment_filename = f"{file_prefix}-{safe_nr}.pdf"

    custom_text_part = f"\n\n--- Wiadomość od obsługi klienta ---\n{custom_message}\n----------------------------------\n" if custom_message else ""
    text_content = f"""Dzień dobry,

W załączniku przesyłamy dokument księgowy: {doc_title} nr {doc_number} do zamówienia #{order_id}.{custom_text_part}

Dokument został dołączony w formacie PDF.

Pozdrawiamy,
Zespół MiniTech Store
http://127.0.0.1:18070
"""

    custom_html_box = f"""
    <div style="margin: 20px 0; padding: 14px 18px; background: #f8fafc; border-left: 4px solid #0ea5e9; border-radius: 4px; font-style: normal;">
      <strong style="color: #0284c7; display: block; margin-bottom: 6px; font-size: 13px;">Wiadomość od obsługi klienta MiniTech:</strong>
      <p style="margin: 0; color: #334155; font-size: 14px; line-height: 1.5; white-space: pre-wrap;">{html.escape(custom_message)}</p>
    </div>
    """ if custom_message else ""

    buyer_row = f"""
          <tr>
            <td style="padding: 4px 0; color: #64748b;">Nabywca:</td>
            <td style="padding: 4px 0; font-weight: 600; text-align: right; color: #0f172a;">{html.escape(buyer_name)}</td>
          </tr>
    """ if buyer_name else ""

    html_content = f"""<!DOCTYPE html>
<html>
<head><meta charset="utf-8"></head>
<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; color: #1e293b; background-color: #f1f5f9; padding: 24px; margin: 0;">
  <div style="max-width: 600px; margin: 0 auto; background: #ffffff; border-radius: 10px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 4px 6px rgba(0,0,0,0.05);">
    <div style="background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%); padding: 22px 28px; color: #ffffff;">
      <h1 style="margin: 0; font-size: 20px; font-weight: 700; letter-spacing: -0.02em;">MiniTech Store</h1>
      <p style="margin: 4px 0 0; color: #94a3b8; font-size: 13px;">Elektroniczny obieg dokumentów księgowych</p>
    </div>
    <div style="padding: 28px;">
      <h2 style="margin: 0 0 16px; font-size: 18px; color: #0f172a;">Dzień dobry,</h2>
      <p style="margin: 0 0 16px; font-size: 14px; line-height: 1.6; color: #475569;">
        Przesyłamy dokument księgowy: <strong style="color: #0ea5e9;">{html.escape(doc_title)} nr {html.escape(doc_number)}</strong> wystawiony do zamówienia <strong>#{order_id}</strong>.
      </p>

      {custom_html_box}

      <div style="background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 14px 18px; margin: 20px 0;">
        <table style="width: 100%; border-collapse: collapse; font-size: 13px;">
          <tr>
            <td style="padding: 4px 0; color: #64748b;">Typ dokumentu:</td>
            <td style="padding: 4px 0; font-weight: 600; text-align: right; color: #0f172a;">{html.escape(doc_title)}</td>
          </tr>
          <tr>
            <td style="padding: 4px 0; color: #64748b;">Numer dokumentu:</td>
            <td style="padding: 4px 0; font-weight: 700; text-align: right; font-family: monospace; color: #0284c7;">{html.escape(doc_number)}</td>
          </tr>
          <tr>
            <td style="padding: 4px 0; color: #64748b;">ID Zamówienia:</td>
            <td style="padding: 4px 0; font-weight: 600; text-align: right; color: #0f172a;">#{order_id}</td>
          </tr>
          {buyer_row}
          <tr>
            <td style="padding: 4px 0; color: #64748b;">Załączony plik:</td>
            <td style="padding: 4px 0; font-weight: 600; text-align: right; color: #16a34a;">📎 {attachment_filename}</td>
          </tr>
        </table>
      </div>

      <p style="margin: 16px 0 0; font-size: 13px; color: #64748b; line-height: 1.5;">
        Plik PDF z pełną treścią dokumentu znajduje się w załączniku niniejszej wiadomości.
      </p>
    </div>
    <div style="background: #f8fafc; border-top: 1px solid #e2e8f0; padding: 16px 28px; text-align: center; font-size: 12px; color: #94a3b8;">
      MiniTech Store Sp. z o.o. • Mini Order Platform (Control Center)<br>
      Wiadomość wygenerowana automatycznie.
    </div>
  </div>
</body>
</html>
"""

    part_text = MIMEText(text_content, "plain", "utf-8")
    part_html = MIMEText(html_content, "html", "utf-8")
    msg.attach(part_text)
    msg.attach(part_html)

    part_pdf = MIMEApplication(pdf_bytes, _subtype="pdf")
    part_pdf.add_header("Content-Disposition", "attachment", filename=attachment_filename)
    msg.attach(part_pdf)

    # 3. Dispatch via SMTP to Mailpit
    try:
        with smtplib.SMTP("127.0.0.1", 11025, timeout=10.0) as smtp:
            smtp.send_message(msg)
    except Exception as exc:
        return {"success": False, "error": f"Błąd połączenia z serwerem poczty Mailpit (port 11025): {exc}"}

    # 4. Save history record
    rec = {
        "id": str(uuid.uuid4())[:8],
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime()),
        "documentType": doc_type,
        "documentId": int(doc_id),
        "orderId": int(order_id),
        "status": "SENT",
    }
    recorded=services.record_dispatch(rec)

    return {"success": True, "record": rec,"historyRecorded":bool(recorded)}

def get_inventory_data(services, params: dict) -> dict[str, Any]:
    cursor, size = int(params["cursor"]), int(params["size"])
    ceiling = cursor - 1 if cursor else 9223372036854775807
    search = params.get("search", "")
    # Product names are resolved only for this window; the catalog and its images are not loaded.
    product_filter = ""
    if search:
        if not re.fullmatch(r"[0-9]{1,19}", search) or int(search) > 9223372036854775807:
            raise ValueError("Filtr magazynu: podaj ID produktu.")
        product_filter = f" AND product_id = {int(search)}"
    rows = services.psql_query_json("inventory", f"SELECT id, product_id, available_quantity, reserved_quantity, updated_at FROM inventory_items WHERE id <= {ceiling}{product_filter} ORDER BY id DESC LIMIT {size + 1}")
    more = len(rows) > size
    items = rows[:size]
    ids = [int(i["product_id"]) for i in items]
    products = services.psql_query_json("product", "SELECT id, name, sku, price, category FROM products WHERE id IN (" + ",".join(map(str, ids)) + ")") if ids else []
    by_id = {p["id"]: p for p in products}
    for item in items:
        info = by_id.get(item["product_id"], {})
        item.update({key: info.get(key, "—") for key in ("name", "sku", "price", "category")})
    return {"inventory": items, "hasMore": more, "nextCursor": str(items[-1]["id"]) if more else "0", "size": size}

def restock_inventory(services, product_id: int, quantity: int, operation_id: str) -> dict[str, Any]:
    return services.receive_stock(services.api_json, product_id, quantity, operation_id)

def get_customers_data(services, params: dict) -> dict[str, Any]:
    return services.read_customer_page(services.api_page, services.GATEWAY_API, params)
