"""Loopback HTTP policy and bounded, in-memory sessions for the local control panel."""
from collections import OrderedDict
from dataclasses import dataclass
import hashlib
import ipaddress
import re
import secrets
import threading
import time
from urllib.parse import urlsplit


class PanelRejected(Exception):
    def __init__(self, status, code, message):
        self.status, self.code, self.message = status, code, message


class LocalRequestPolicy:
    HOSTS = {'127.0.0.1', 'localhost', '::1'}

    def __init__(self, port):
        self.port = port

    def authority(self, value):
        try:
            if not value or len(value) > 256 or any(ord(char) <= 32 or ord(char) == 127 for char in value):
                raise ValueError()
            if not re.fullmatch(r'(?:127\.0\.0\.1|localhost|\[::1\])(?::[0-9]{1,5})?', value, re.IGNORECASE):
                raise ValueError()
            parsed = urlsplit('http://' + value)
            if parsed.username is not None or parsed.password is not None or parsed.path or parsed.query or parsed.fragment:
                raise ValueError()
            host = parsed.hostname
            port = parsed.port if parsed.port is not None else 80
            if host not in self.HOSTS or port != self.port:
                raise ValueError()
            return f'[{host}]:{port}' if ':' in host else f'{host}:{port}'
        except ValueError:
            raise PanelRejected(403, 'CC_INVALID_HOST', 'Niedozwolony adres panelu lokalnego.') from None

    def validate(self, headers, path, peer):
        try:
            if not ipaddress.ip_address(peer).is_loopback: raise ValueError()
        except ValueError:
            raise PanelRejected(403, 'CC_LOOPBACK_REQUIRED', 'Wymagane połączenie lokalne.') from None
        hosts = headers.get_all('Host', [])
        if len(hosts) != 1:
            raise PanelRejected(400, 'CC_INVALID_HOST', 'Wymagany jest jeden nagłówek Host.')
        authority = self.authority(hosts[0])
        if not path.startswith('/') or path.startswith('//'):
            raise PanelRejected(400, 'CC_INVALID_TARGET', 'Niedozwolony adres żądania.')
        origins = headers.get_all('Origin', [])
        if len(origins) > 1:
            raise PanelRejected(403, 'CC_INVALID_ORIGIN', 'Niedozwolone pochodzenie żądania.')
        if origins:
            try:
                if len(origins[0]) > 512 or any(ord(char) <= 32 or ord(char) == 127 for char in origins[0]):
                    raise ValueError()
                origin = urlsplit(origins[0])
                if origin.scheme != 'http' or not origin.netloc or origin.path or origin.query or origin.fragment:
                    raise ValueError()
                if origins[0].lower() != ('http://' + origin.netloc).lower(): raise ValueError()
                if self.authority(origin.netloc) != authority: raise ValueError()
            except (ValueError, PanelRejected):
                raise PanelRejected(403, 'CC_INVALID_ORIGIN', 'Niedozwolone pochodzenie żądania.') from None
        sites = headers.get_all('Sec-Fetch-Site', [])
        if len(sites) > 1 or (sites and sites[0] not in {'same-origin', 'none'}):
            raise PanelRejected(403, 'CC_CROSS_SITE', 'Żądanie musi pochodzić z lokalnego panelu.')
        if path.split('?', 1)[0].startswith('/api/') and headers.get('Sec-Fetch-Mode') == 'navigate':
            raise PanelRejected(403, 'CC_API_NAVIGATION', 'API panelu wymaga żądania aplikacji.')
        return authority


@dataclass
class Session:
    authority: str
    created: float
    seen: float


class PanelSessions:
    def __init__(self, capacity=64, idle_seconds=1800, lifetime_seconds=28800, clock=time.monotonic):
        if min(capacity, idle_seconds, lifetime_seconds) <= 0: raise ValueError('Session limits must be positive')
        self.capacity, self.idle, self.lifetime, self.clock = capacity, idle_seconds, lifetime_seconds, clock
        self.sessions = OrderedDict()
        self.lock = threading.Lock()

    def _expired(self, session, now):
        return now - session.created >= self.lifetime or now - session.seen >= self.idle

    @staticmethod
    def _hash(token):
        return hashlib.sha256(token.encode('ascii')).hexdigest()

    def issue(self, authority):
        now = self.clock()
        with self.lock:
            for key, value in list(self.sessions.items()):
                if self._expired(value, now): del self.sessions[key]
            while len(self.sessions) >= self.capacity: self.sessions.popitem(last=False)
            token = secrets.token_urlsafe(32)
            self.sessions[self._hash(token)] = Session(authority, now, now)
        return {'token': token, 'idleExpiresIn': self.idle, 'absoluteExpiresIn': self.lifetime}

    def authorize(self, token, authority):
        if not isinstance(token, str) or not re.fullmatch(r'[A-Za-z0-9_-]{43}', token):
            raise PanelRejected(401, 'CC_SESSION_EXPIRED', 'Sesja panelu wygasła lub jest nieprawidłowa. Odśwież panel.')
        key = self._hash(token)
        now = self.clock()
        with self.lock:
            session = self.sessions.get(key)
            if session is None or session.authority != authority or self._expired(session, now):
                if session is not None and self._expired(session, now): del self.sessions[key]
                raise PanelRejected(401, 'CC_SESSION_EXPIRED', 'Sesja panelu wygasła lub jest nieprawidłowa. Odśwież panel.')
            session.seen = now
            self.sessions.move_to_end(key)
        return key

    def revoke(self,token,authority):
        key=self.authorize(token,authority)
        with self.lock:self.sessions.pop(key,None)
