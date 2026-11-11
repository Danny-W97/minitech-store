#!/usr/bin/env python3
"""A36.1: encrypted 14-database + Redis capture, source loss and isolated restore.
Uses only this run's containers, data and age identities. No application profile.
"""
import copy
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('platform_recovery', ROOT / 'scripts/backup/platform-recovery.py')
recovery = importlib.util.module_from_spec(spec); spec.loader.exec_module(recovery)


def main():
    directory = recovery.private_directory(ROOT / 'tmp' / ('audit-a36-' + secrets.token_hex(4)))
    identities = recovery.private_directory(directory / '.private')
    identity = identities / 'fixture.agekey'; wrong_identity = identities / 'wrong.agekey'
    source_containers = []; run_id = 'audit-a36-' + secrets.token_hex(5)
    report = {'status': 'FAILED', 'fullA36Accepted': False, 'automaticReconciliationTested': False}
    run = recovery.run
    order_id = 900001

    def container(image, *args, env=None):
        identifier = run('docker', 'run', '-d', '--network', 'none', '--label', 'minitech.recovery-fixture=' + run_id, *args, image, env=env)
        source_containers.append(identifier)
        return identifier

    def sql(alias, query):
        result = subprocess.run(['docker', 'exec', '-i', postgres, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'audit', '-d', alias + '_db'], input=query.encode(), capture_output=True, timeout=120)
        if result.returncode:
            (directory / (alias + '-fixture-error.log')).write_bytes(result.stderr)
            raise RuntimeError('Fixture SQL failed for ' + alias + '; see private test log in ' + str(directory))
        return result.stdout.decode().strip()

    def redis(*args):
        return json.loads(run('docker', 'exec', broker, 'redis-cli', '-e', '--json', *map(str, args)))

    def rejected(action):
        try: action()
        except (ValueError, RuntimeError): return
        raise AssertionError('Invalid backup was accepted')

    try:
        run('age-keygen', '-o', str(identity)); identity.chmod(0o600)
        run('age-keygen', '-o', str(wrong_identity)); wrong_identity.chmod(0o600)
        recipient = run('age-keygen', '-y', str(identity))
        env = dict(os.environ, POSTGRES_PASSWORD=secrets.token_urlsafe(32))
        postgres = container(recovery.POSTGRES_IMAGE, '-e', 'POSTGRES_USER=audit', '-e', 'POSTGRES_PASSWORD', env=env)
        broker = container(recovery.REDIS_IMAGE)
        for _ in range(60):
            result = subprocess.run(['docker', 'exec', postgres, 'pg_isready', '-U', 'audit'], capture_output=True)
            if result.returncode == 0:
                test_query = subprocess.run(['docker', 'exec', postgres, 'psql', '-U', 'audit', '-d', 'postgres', '-c', 'SELECT 1'], capture_output=True)
                if test_query.returncode == 0: break
            time.sleep(.5)
        else: raise AssertionError('Fixture PostgreSQL startup timeout')
        for _ in range(40):
            result = subprocess.run(['docker', 'exec', broker, 'redis-cli', 'PING'], capture_output=True)
            if result.returncode == 0 and result.stdout.strip() == b'PONG': break
            time.sleep(.25)
        else: raise AssertionError('Fixture Redis startup timeout')
        sources = {'databases': {}, 'redis': {'container': broker}}
        for alias in recovery.aliases():
            for retry in range(20):
                res = subprocess.run(['docker', 'exec', postgres, 'createdb', '-U', 'audit', alias + '_db'], capture_output=True)
                if res.returncode == 0: break
                time.sleep(1)
            else: raise RuntimeError('Fixture createdb failed for ' + alias + ': ' + res.stderr.decode().strip())
            migrations = sorted((ROOT / (alias + '-service') / 'src/main/resources/db/migration').glob('V*__*.sql'), key=lambda p: int(p.name.split('__')[0][1:]))
            assert migrations, alias
            # Match Flyway's transaction boundary (including migrations using LOCK TABLE).
            for path in migrations:
                sql(alias, 'BEGIN;\n' + path.read_text(encoding='utf-8-sig') + '\nCOMMIT;')
            sources['databases'][alias] = {'container': postgres, 'database': alias + '_db', 'user': 'audit'}
        print('PASS fixture: all 14 actual PostgreSQL migration sets applied', flush=True)

        sql('order', f"""INSERT INTO orders(id,idempotency_key,product_id,quantity,unit_price,total_price,status,created_at,updated_at,
            customer_email,delivery_first_name,delivery_last_name,delivery_street,delivery_postal_code,delivery_city,delivery_country,
            delivery_method,delivery_cost,fulfillment_type,invoice_requested,inventory_reservation_id)
            VALUES ({order_id},'recovery-fixture',1,1,10,10,'PAID',now(),now(),'fixture@example.test','Test','Fixture','Test','00-001','Test','PL','STANDARD_COURIER',0,'HOME_DELIVERY',true,800001);
            INSERT INTO order_sagas(order_id,state,created_at,updated_at) VALUES ({order_id},'COMPLETED',now(),now());""")
        sql('payment', f"""INSERT INTO payments(order_id,idempotency_key,amount,currency,payment_method,status,created_at,updated_at,result_recorded_at)
            VALUES ({order_id},'recovery-fixture',10,'PLN','TEST','SUCCEEDED',now(),now(),now());
            INSERT INTO payment_outbox_events(event_key,event_type,order_id,amount,recipient,status,next_attempt_at,created_at,updated_at,published_at)
            VALUES ('recovery-fixture','PAYMENT_SUCCEEDED',{order_id},10,'fixture@example.test','PUBLISHED',now(),now(),now(),now());""")
        sql('inventory', f"INSERT INTO inventory_reservations(id,order_id,status,created_at,updated_at,expires_at) VALUES (800001,{order_id},'CONFIRMED',now(),now(),now()+interval '15 minutes'); INSERT INTO inventory_reservation_items(reservation_id,product_id,quantity) VALUES (800001,1,1)")
        sql('billing', f"""INSERT INTO invoices(order_id,customer_email,invoice_number,billing_kind,buyer_name,street,postal_code,city,country,net_amount,vat_amount,gross_amount,currency,status,created_at)
            VALUES ({order_id},'fixture@example.test','RECOVERY-FIXTURE','PERSONAL','Test Fixture','Test','00-001','Test','PL',8.13,1.87,10,'PLN','ISSUED',now())""")
        sql('notification', f"INSERT INTO notifications(event_type,source_id,recipient,channel,subject,message,status,created_at,updated_at,sent_at) VALUES ('PAYMENT_SUCCEEDED',{order_id},'fixture@example.test','EMAIL','fixture','fixture','SENT',now(),now(),now())")
        sql('delivery', f"INSERT INTO shipments(order_id,tracking_number,carrier,method_code,status,created_at,updated_at) VALUES ({order_id},'RECOVERY-FIXTURE','fixture','STANDARD_COURIER','CREATED',now(),now())")
        redis('XADD', 'payment-events', '1-0', 'eventId', 'recovery-fixture', 'orderId', order_id)
        redis('XGROUP', 'CREATE', 'payment-events', 'notification-service', '0')
        redis('XREADGROUP', 'GROUP', 'notification-service', 'fixture-consumer', 'COUNT', 1, 'STREAMS', 'payment-events', '>')
        redis('XADD', 'invoice-events', '1-0', 'eventId', 'invoice-fixture')
        redis('XGROUP', 'CREATE', 'invoice-events', 'notification-service', '0')
        redis('SET', 'fixture-cache', 'not-a-user-secret', 'EX', 86400)

        # pg_dump fails while age can still successfully encrypt the empty stream.
        sql('billing', 'CREATE ROLE restricted LOGIN')
        restricted = copy.deepcopy(sources); restricted['databases']['billing']['user'] = 'restricted'
        partial_root = directory / 'failed-capture'
        rejected(lambda: recovery.capture(restricted, recipient, partial_root))
        assert not list(partial_root.glob('*/manifest.json.age'))
        report['failedDumpDoesNotCompleteSet'] = True

        bundle = recovery.capture(sources, recipient, directory / 'sets')
        manifest = recovery.load_manifest(bundle, identity)
        assert len(manifest['artifacts']) == 15
        assert {path.suffix for path in bundle.iterdir()} == {'.age'}
        report['encryptedArtifacts'] = 15; report['encryptedManifest'] = True
        report['captureWindowSeconds'] = (recovery.datetime.fromisoformat(manifest['finishedAt']) - recovery.datetime.fromisoformat(manifest['startedAt'])).total_seconds()
        print('PASS encrypted set: 14 databases + Redis, authenticated manifest and failed producer detection', flush=True)

        rejected(lambda: recovery.load_manifest(bundle, wrong_identity))
        corrupt = directory / 'corrupt'; shutil.copytree(bundle, corrupt)
        path = corrupt / 'payment.dump.age'
        data = bytearray(path.read_bytes()); data[-1] ^= 1; path.write_bytes(data)
        rejected(lambda: recovery.Restored(corrupt, identity))
        missing = directory / 'missing-redis'; shutil.copytree(bundle, missing); (missing / 'redis.rdb.age').unlink()
        rejected(lambda: recovery.Restored(missing, identity))
        missing_db = directory / 'missing-payment'; shutil.copytree(bundle, missing_db); (missing_db / 'payment.dump.age').unlink()
        rejected(lambda: recovery.Restored(missing_db, identity))
        report['rejectedWrongKeyCorruptionAndMissingParts'] = True

        # Destroy only this test's sources: subsequent assertions must rely on backups.
        for identifier in source_containers: run('docker', 'rm', '-f', '-v', identifier)
        source_containers.clear()
        restored_report = recovery.drill(bundle, identity, directory / 'restore-report.json', [order_id])
        assert restored_report['status'] == 'PASS' and restored_report['businessVerified']
        assert len(restored_report['databaseTables']) == 14
        assert restored_report['streams']['payment-events'] == {'entries': 1, 'groups': 1, 'pending': 1}
        report['knownOrderRecoveredAfterSourceLoss'] = True
        report['redisGroupsAndPendingRecovered'] = True
        report['restoreDurationSeconds'] = restored_report['durationSeconds']
        print('PASS after source loss: 14 restored schemas, payment/reservation/invoice/notification/shipment and Redis pending', flush=True)

        with recovery.Restored(bundle, identity) as restored:
            for identifier in restored.containers:
                facts = json.loads(run('docker', 'inspect', '--format', '{{json .HostConfig}}', identifier))
                assert facts['NetworkMode'] == 'none' and not facts['PortBindings'] and not facts['Binds']
            assert recovery.business_witness(restored, order_id)['passed']
            restored.sql('payment', f'DELETE FROM payments WHERE order_id={order_id}')
            witness = recovery.business_witness(restored, order_id)
            assert not witness['passed'] and not witness['checks']['successfulPayment']
            assert witness['checks']['issuedInvoice'] and witness['checks']['sentPaymentNotification']
            report['missingPaymentRequiresReconciliation'] = True
            # Source loss of one whole database cannot be accepted as a technical success.
            restored.sql('payment', 'DROP SCHEMA public CASCADE; CREATE SCHEMA public')
            rejected(lambda: recovery.business_witness(restored, order_id))
            report['missingDatabaseSchemaRejected'] = True
        report['noNetworkPortsOrHostBinds'] = True
        report['status'] = 'PASS'
        print('PASS divergence detection and isolated cleanup; full A36 acceptance remains open', flush=True)
    finally:
        for identifier in source_containers:
            subprocess.run(['docker', 'rm', '-f', '-v', identifier], capture_output=True)
        identity.unlink(missing_ok=True); wrong_identity.unlink(missing_ok=True)
        identities.rmdir()
        with (directory / 'report.json').open('w') as output:
            (directory / 'report.json').chmod(0o600); json.dump(report, output, indent=2)
        print('Report: ' + str(directory / 'report.json'), flush=True)


if __name__ == '__main__': main()
