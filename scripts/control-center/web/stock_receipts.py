"""Idempotent receipt proxy. An ambiguous write is resolved only through its ledger."""
import re
import urllib.error
import uuid


class StockOperationError(RuntimeError):
    def __init__(self, message, status=503, operation_id=None, code='STOCK_OPERATION_UNCONFIRMED'):
        super().__init__(message)
        self.status, self.operation_id, self.code = status, operation_id, code


def positive(value, maximum, label):
    if type(value) is int:
        result = value
    elif isinstance(value, str) and value.isascii() and value.isdecimal():
        result = int(value)
    else:
        raise ValueError(f'{label}: wymagana dodatnia liczba całkowita.')
    if not 1 <= result <= maximum:
        raise ValueError(f'{label}: wartość poza zakresem.')
    return result


def receipt_key(value):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', value):
        raise ValueError('Wymagany identyfikator operacji UUID.')
    return str(uuid.UUID(value))


def receive(api_json, product_id, quantity, operation_id):
    product_id = positive(product_id, 9223372036854775807, 'ID produktu')
    quantity = positive(quantity, 2147483647, 'Ilość')
    operation_id = receipt_key(operation_id)

    def verified(row):
        if not isinstance(row, dict) or row.get('status') != 'COMMITTED':
            raise StockOperationError(f'Nie potwierdzono przyjęcia. Ponów tę samą operację {operation_id}.', operation_id=operation_id)
        if row.get('operationId') != operation_id or row.get('productId') != product_id or row.get('quantity') != quantity or row.get('kind') != 'RECEIPT':
            raise StockOperationError(f'Identyfikator {operation_id} opisuje inną operację. Sprawdź rejestr magazynu.', 409, operation_id, 'STOCK_OPERATION_REJECTED')
        return {'ok': True, **row, 'newQuantity': row.get('availableQuantity'), 'method': 'REST'}

    try:
        result = api_json(f'http://127.0.0.1:18083/inventory/items/{product_id}/receipts', method='POST',
                          payload={'quantity': quantity, 'operationId': operation_id}, timeout=10)
    except urllib.error.HTTPError as error:
        if 400 <= error.code < 500 and error.code not in {408, 425, 429}:
            raise StockOperationError(f'Przyjęcie odrzucone (HTTP {error.code}). Operacja: {operation_id}.', error.code, operation_id, 'STOCK_OPERATION_REJECTED') from error
    except Exception:
        pass
    else:
        return verified(result)

    # A 404 may race a still-running POST; it never licenses a new operation ID.
    try:
        result = api_json(f'http://127.0.0.1:18083/inventory/operations/{operation_id}', timeout=5)
    except Exception as error:
        raise StockOperationError(f'Wynik niepotwierdzony. Ponów tę samą operację {operation_id}; nie twórz nowej dostawy.', operation_id=operation_id) from error
    return verified(result)
