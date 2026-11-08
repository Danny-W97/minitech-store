"""A23 HTTP assertions for the disposable MFA test runtime, including two JVMs."""
import concurrent.futures
from http.cookies import SimpleCookie
import json
import threading
import time
import urllib.error
import urllib.request


def run_checks(http, urls, login, secret, recovery, customer_id, sql, delete_enrollment, totp_code):
    before = http.CHECKS
    api, replica = urls['customer-service'], urls['customer-replica']

    def call(base, path, body=None, cookie=None, token=None, expected=200):
        headers = {'Content-Type': 'application/json'}
        if cookie: headers['Cookie'] = cookie
        if token: headers['Authorization'] = 'Bearer ' + token
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(base + path, data=data, headers=headers, method='POST')
        try:
            with urllib.request.urlopen(req, timeout=25) as response:
                status, content, outgoing = response.status, response.read(), response.headers.get('Set-Cookie')
        except urllib.error.HTTPError as error:
            status, content, outgoing = error.code, error.read(), error.headers.get('Set-Cookie')
        accepted = expected if isinstance(expected, (tuple, list)) else (expected,)
        assert status in accepted, f'{path}: expected {accepted}, got {status}'
        http.CHECKS += 1
        cookie_jar = SimpleCookie(outgoing or '')
        result_cookie = '; '.join(f'{key}={value.value}' for key, value in cookie_jar.items()) or None
        return status, json.loads(content) if content else None, result_cookie

    def challenge(base=api, credentials=login):
        return call(base, '/customers/login', credentials, expected=202)[1]['challengeId']

    def recover(index, base=api):
        return call(base, '/customers/mfa/login/verify', {'challengeId': challenge(base), 'code': recovery[index]})

    def race(path, bodies, *, cookie=None, token=None, accepted=(200, 401)):
        gate = threading.Barrier(2)
        def attempt(item):
            base, body = item
            gate.wait(timeout=15)
            return call(base, path, body, cookie=cookie, token=token, expected=accepted)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(attempt, zip([api, replica], bodies)))
        assert sorted(result[0] for result in results) == sorted(accepted), 'Exactly one operation must succeed'
        return next(result for result in results if result[0] == 200)

    def fresh_code():
        last = int(sql(f'SELECT last_used_time_step FROM customer_totp_methods WHERE customer_id={customer_id}'))
        while last >= int(time.time()) // 30 + 1: time.sleep(1)
        return totp_code(max(last + 1, int(time.time()) // 30))

    # Different login challenges cannot bypass the global single-use TOTP time step.
    proof = fresh_code()
    challenges = [challenge(api), challenge(replica)]
    rows_before = int(sql('SELECT count(*) FROM refresh_tokens'))
    totp_session = race('/customers/mfa/login/verify', [{'challengeId': item, 'code': proof} for item in challenges])
    assert int(sql('SELECT count(*) FROM refresh_tokens')) == rows_before + 1
    token = totp_session[1]['accessToken']
    cookie = totp_session[2]
    assert cookie
    # One refresh succeeds; replay detection revokes that family's newly issued refresh token too.
    rows_before = int(sql('SELECT count(*) FROM refresh_tokens'))
    rotated = race('/customers/refresh', [None, None], cookie=cookie)
    assert int(sql('SELECT count(*) FROM refresh_tokens')) == rows_before + 1
    call(api, '/customers/refresh', cookie=rotated[2], expected=401)
    assert int(sql("SELECT count(*) FROM audit_events WHERE event_type='REFRESH_TOKEN_REUSE'")) >= 1
    independent = recover(2)
    call(replica, '/customers/refresh', cookie=cookie, expected=401)
    descendant = call(api, '/customers/refresh', cookie=independent[2])
    call(replica, '/customers/logout', cookie=independent[2], expected=204)
    call(api, '/customers/refresh', cookie=descendant[2], expected=401)
    print('PASS two-instance refresh rotation, reuse revocation, family isolation and logout', flush=True)

    # Recovery codes are global to the customer, independent of challenge and JVM.
    challenges = [challenge(api), challenge(replica)]
    rows_before = int(sql('SELECT count(*) FROM refresh_tokens'))
    race('/customers/mfa/login/verify', [{'challengeId': item, 'code': recovery[3]} for item in challenges])
    assert int(sql('SELECT count(*) FROM refresh_tokens')) == rows_before + 1
    call(api, '/customers/mfa/login/verify', {'challengeId': challenges[0], 'code': recovery[3]}, expected=401)
    hashes_before = sql(f'SELECT string_agg(code_hash, CHR(44) ORDER BY code_hash) FROM customer_recovery_codes WHERE customer_id={customer_id}')
    call(api, '/customers/mfa/recovery-codes/regenerate', token=token, expected=400)
    wrong = '000000'
    while wrong in {totp_code(int(time.time()) // 30 + offset) for offset in [-1, 0, 1]}:
        wrong = f'{int(wrong) + 1:06d}'
    call(replica, '/customers/mfa/recovery-codes/regenerate', {'code': wrong}, token=token, expected=401)
    assert sql(f'SELECT string_agg(code_hash, CHR(44) ORDER BY code_hash) FROM customer_recovery_codes WHERE customer_id={customer_id}') == hashes_before
    proof = fresh_code()
    regenerated = race('/customers/mfa/recovery-codes/regenerate', [{'code': proof}, {'code': proof}], token=token)
    assert len(regenerated[1]['codes']) == 8
    assert int(sql(f'SELECT count(*) FROM customer_recovery_codes WHERE customer_id={customer_id} AND used_at IS NULL')) == 8
    call(api, '/customers/mfa/login/verify', {'challengeId': challenge(), 'code': proof}, expected=401)
    call(api, '/customers/mfa/login/verify', {'challengeId': challenge(), 'code': recovery[4]}, expected=401)
    print('PASS two-instance recovery-code consumption and fresh TOTP confirmation for regeneration', flush=True)

    # A second account exercises expired setup and retry without altering the main account's factors.
    credentials = dict(email='ttl@audit.example', password=login['password'])
    http.request(api + '/customers/register', 'POST', dict(credentials, firstName='TTL', lastName='Tester',
                 street='Testowa 1', postalCode='00-001', city='Warszawa', country='Polska'), expected=201)
    sql("UPDATE customers SET enabled=true WHERE email='ttl@audit.example'")
    session = call(api, '/customers/login', credentials)[1]
    ttl_id, ttl_token = session['customer']['id'], session['accessToken']
    def enrollment(): return call(api, '/customers/mfa/totp/enroll', token=ttl_token)[1]
    pending = enrollment()
    def pending_code(): return totp_code(int(time.time()) // 30, pending['secret'])
    sql(f"UPDATE customer_totp_methods SET enrollment_expires_at=now()-interval '1 second' WHERE customer_id={ttl_id}")
    call(replica, '/customers/mfa/totp/confirm', {'code': pending_code()}, token=ttl_token, expected=410)
    assert sql(f'SELECT enabled FROM customer_totp_methods WHERE customer_id={ttl_id}') == 'f'
    pending = enrollment()
    delete_enrollment(ttl_id)
    call(replica, '/customers/mfa/totp/confirm', {'code': pending_code()}, token=ttl_token, expected=410)
    pending = enrollment()
    sql(f'UPDATE customer_totp_methods SET enrollment_expires_at=NULL WHERE customer_id={ttl_id}')
    call(api, '/customers/mfa/totp/confirm', {'code': pending_code()}, token=ttl_token, expected=410)
    pending = enrollment()
    wrong = '000000'
    while wrong in {totp_code(int(time.time()) // 30 + offset, pending['secret']) for offset in [-1, 0, 1]}:
        wrong = f'{int(wrong) + 1:06d}'
    call(api, '/customers/mfa/totp/confirm', {'code': wrong}, token=ttl_token, expected=401)
    proof = pending_code()
    confirmed = race('/customers/mfa/totp/confirm', [{'code': proof}, {'code': proof}], token=ttl_token, accepted=(200, 409))
    assert len(confirmed[1]['codes']) == 8
    assert int(sql(f'SELECT count(*) FROM customer_recovery_codes WHERE customer_id={ttl_id}')) == 8
    print('PASS enrollment expiry in DB/Redis, legacy setup invalidation, retry and simultaneous confirmation', flush=True)
    print(f'PASS: A23, {http.CHECKS-before} HTTP checks across two Customer Service instances sharing PostgreSQL and Redis.', flush=True)
