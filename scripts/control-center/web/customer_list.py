"""Read the operator's customer page without SQL fallbacks or invented fields."""
import subprocess
import urllib.error


class CustomerListError(RuntimeError):
    def __init__(self, message, status=502):
        super().__init__(message)
        self.status = status


def read_page(api_page, gateway_api, params):
    try:
        page = api_page(gateway_api + '/customers', params)
    except urllib.error.HTTPError as error:
        if error.code in (401, 403):
            raise CustomerListError('Nie potwierdzono uprawnień operatora do listy klientów.', error.code) from error
        raise CustomerListError('Usługa klientów odrzuciła odczyt listy. Spróbuj ponownie.') from error
    except PermissionError as error:
        raise CustomerListError('Nie potwierdzono uprawnień operatora do listy klientów.', 403) from error
    except (ValueError, TypeError, KeyError) as error:
        raise CustomerListError('Usługa klientów zwróciła nieprawidłowe dane.') from error
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        raise CustomerListError('Lista klientów jest niedostępna. Sprawdź usługi i konfigurację operatora.', 503) from error

    customers = []
    for row in page['items']:
        if (not isinstance(row, dict) or type(row.get('id')) is not int or row['id'] <= 0
                or any(not isinstance(row.get(key), str) for key in ('email', 'firstName', 'lastName', 'city', 'createdAt'))
                or not row['email'] or not row['createdAt']):
            raise CustomerListError('Usługa klientów zwróciła nieprawidłowy rekord.')
        customers.append({'id': row['id'], 'email': row['email'], 'first_name': row['firstName'],
                          'last_name': row['lastName'], 'city': row['city'], 'created_at': row['createdAt']})
    return {'customers': customers, **{key: value for key, value in page.items() if key != 'items'}}
