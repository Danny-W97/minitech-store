"""Preserve a correction command across transport retries; never invent its key."""
from decimal import Decimal, InvalidOperation
import json
import re
import urllib.error


class CorrectionError(Exception):
    def __init__(self, status, message, definitive=False):
        super().__init__(message)
        self.status, self.definitive = status, definitive


def issue(api, bases, invoice_id, reason, delta, key, version):
    if isinstance(invoice_id, bool) or not re.fullmatch(r'[1-9][0-9]{0,17}', str(invoice_id)):
        raise ValueError('Nieprawidłowe ID faktury.')
    if not isinstance(reason, str) or not reason.strip() or len(reason) > 255 or any(ord(c)<32 or ord(c)==127 for c in reason):
        raise ValueError('Przyczyna musi mieć od 1 do 255 znaków bez znaków sterujących.')
    if not isinstance(key, str) or not re.fullmatch(r'[A-Za-z0-9_-]{8,100}', key):
        raise ValueError('Wymagany jest stały klucz operacji (8–100 znaków).')
    if isinstance(version, bool) or not re.fullmatch(r'[0-9]{1,16}', str(version)) or int(version)>9007199254740991:
        raise ValueError('Wymagana jest odczytana wersja salda.')
    try:
        amount=Decimal(str(delta))
        if not amount.is_finite() or amount==0 or abs(amount)>Decimal('99999999999999999.99') or amount.as_tuple().exponent < -2:
            raise ValueError('Nieprawidłowa kwota korekty: wymagane maksymalnie dwa miejsca po przecinku.')
    except InvalidOperation as error:
        raise ValueError('Nieprawidłowa kwota korekty.') from error
    payload=dict(reason=reason.strip(),correctionGrossAmount=format(amount,'.2f'),idempotencyKey=key,expectedVersion=int(version))
    for base in bases:
        try:
            result=api(f'{base}/invoices/{int(invoice_id)}/corrections',method='POST',payload=payload,timeout=10)
        except urllib.error.HTTPError as error:
            if error.code>=500: continue
            try: code=json.loads(error.read().decode('utf-8')).get('code')
            except Exception: code=None
            definitive=code=='CORRECTION_BALANCE_CHANGED' or error.code==400
            message='Saldo zmieniło się. Otwórz formularz ponownie i sprawdź nowe kwoty.' if code=='CORRECTION_BALANCE_CHANGED' else f'Billing odrzucił korektę (HTTP {error.code}).'
            raise CorrectionError(error.code,message,definitive) from error
        except (OSError, TimeoutError): continue
        try: result_amount=Decimal(str(result.get('correctionGross'))) if isinstance(result,dict) else None
        except InvalidOperation: result_amount=None
        if (not isinstance(result,dict) or result.get('invoiceId')!=int(invoice_id) or result.get('idempotencyKey')!=key
                or result.get('balanceVersion')!=int(version)+1 or result.get('balanceModel')!='CUMULATIVE_DELTA'
                or result_amount!=amount):
            raise CorrectionError(503,'Nie potwierdzono tej operacji. Ponów z tym samym kluczem.')
        return dict(success=True,correction=result)
    raise CorrectionError(503,'Wynik korekty jest nieznany. Ponów z tym samym kluczem i danymi.')
