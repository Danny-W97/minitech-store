"""Per-turn AI egress review. Only digests live in the bounded session registry."""
from dataclasses import dataclass
import hashlib
import hmac
import json
import secrets
import threading
import time
import urllib.request
from urllib.parse import quote

class AiDeliveryError(RuntimeError):
    def __init__(self, code='CC_AI_REVIEW_REQUIRED', status=409):
        self.code, self.status = code, status
        messages = {
            'CC_AI_INVALID_TURN': 'Nieprawidłowa wiadomość lub historia AI. Wiadomość może mieć do 12000 znaków. / Invalid AI message or history; message limit is 12000 characters.',
            'CC_AI_TURN_LIMIT': 'Wiadomość i historia są zbyt duże. Skróć pytanie lub wyczyść historię. / Shorten the question or clear the history.',
            'CC_AI_REVIEW_CAPACITY': 'Zbyt wiele oczekujących podglądów AI. Spróbuj ponownie za kilka minut. / Too many pending AI previews. Try again in a few minutes.',
        }
        super().__init__(messages.get(code, 'Wymagany aktualny podgląd i potwierdzenie tej tury AI. / Review and confirm this AI turn again.'))

def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')


def digest(value):
    return hashlib.sha256(value).hexdigest()

@dataclass(frozen=True, repr=False)
class PreparedDelivery:
    provider: str
    model: str
    target: str
    payload: bytes
    headers: dict
    binding: str
    history_included: bool

    @property
    def external(self):
        return bool(self.target)

    def public_view(self):
        if not self.external:
            return {'mode': 'local', 'externalDelivery': False}
        return {'mode': 'external', 'externalDelivery': True, 'provider': self.provider,
                'model': self.model, 'target': self.target, 'wirePayload': json.loads(self.payload),
                'historyIncluded': self.history_included, 'providerErasureConfirmed': False}

@dataclass(frozen=True, repr=False)
class ApprovedDelivery:
    binding: str

class AiReviewRegistry:
    def __init__(self, capacity=64, ttl=300, clock=time.monotonic):
        if capacity < 1 or ttl < 1:
            raise ValueError('Invalid review limits')
        self.capacity = capacity
        self.ttl = ttl
        self.clock = clock
        self.pending = {}
        self.lock = threading.Lock()

    def issue(self, session, plan):
        if not session or not plan.external:
            raise AiDeliveryError()
        with self.lock:
            now = self.clock()
            self.pending = {key: record for key, record in self.pending.items() if record[2] > now}
            if session not in self.pending and len(self.pending) >= self.capacity:
                raise AiDeliveryError('CC_AI_REVIEW_CAPACITY', 429)
            token = secrets.token_urlsafe(32)
            self.pending[session] = (digest(token.encode()), plan.binding, now + self.ttl)
            return {'reviewToken': token, 'expiresIn': self.ttl}

    def consume(self, session, token, plan):
        with self.lock:
            # Every claim consumes this session's pending review, also on mismatch.
            record = self.pending.pop(session, None)
            if (not record or not isinstance(token, str) or len(token) != 43
                    or record[2] <= self.clock()
                    or not hmac.compare_digest(record[0], digest(token.encode()))
                    or not hmac.compare_digest(record[1], plan.binding)):
                raise AiDeliveryError()
            return ApprovedDelivery(plan.binding)

    def clear(self, session=None):
        with self.lock:
            if session is None:
                self.pending.clear()
            else:
                self.pending.pop(session, None)


def prepare(config, system_prompt, message, history, lang):
    if (not isinstance(message, str) or not message.strip() or len(message) > 12000
            or not isinstance(history, list) or len(history) > 10):
        raise AiDeliveryError('CC_AI_INVALID_TURN', 400)
    for item in history:
        if (not isinstance(item, dict) or set(item) != {'role', 'content'}
                or item['role'] not in {'user', 'assistant'}
                or not isinstance(item['content'], str) or len(item['content']) > 12000):
            raise AiDeliveryError('CC_AI_INVALID_TURN', 400)
    turn = {'message': message, 'history': history, 'language': lang}
    if len(canonical(turn)) > 65536:
        raise AiDeliveryError('CC_AI_TURN_LIMIT', 413)

    provider = config.get('provider', 'antigravity')
    model = config.get('model', 'antigravity-expert')
    key = config.get('apiKey', '').strip()
    if config.get('includeContext', True):
        system = system_prompt(lang)
    else:
        system = ('You are an expert AI software engineering assistant.' if lang == 'en'
                  else 'Jesteś ekspertem i asystentem inżynierii oprogramowania.')
    target = ''
    payload = {}
    headers = {'Content-Type': 'application/json'}
    history_included = False
    if provider in {'cursor', 'cursor_custom'} and config.get('customEndpoint'):
        target = config['customEndpoint'].rstrip('/') + '/chat/completions'
        model = model or 'default'
    elif provider == 'codex' and key:
        target = 'https://api.openai.com/v1/chat/completions'
        model = model or 'gpt-4o'
    elif provider == 'antigravity' and key:
        model = model if model != 'antigravity-expert' else 'gemini-2.5-flash'
        target = ('https://generativelanguage.googleapis.com/v1beta/models/'
                  + quote(model, safe='-._') + ':generateContent')
        payload = {'contents': [{'role': 'user', 'parts': [
            {'text': 'System context:\n' + system + '\n\nUser request:\n' + message}]}]}
        headers['x-goog-api-key'] = key
    if target and provider in {'codex', 'cursor', 'cursor_custom'}:
        payload = {'model': model, 'messages': [{'role': 'system', 'content': system},
                   *history, {'role': 'user', 'content': message}], 'temperature': 0.3}
        history_included = True
        if key:
            headers['Authorization'] = 'Bearer ' + key
    wire = canonical(payload)
    if len(wire) > 131072:
        raise AiDeliveryError('CC_AI_TURN_LIMIT', 413)
    # Key/profile changes invalidate a review without exposing a key fingerprint to the browser.
    binding = digest(canonical({'turn': turn, 'target': target, 'payloadHash': digest(wire), 'config': config}))
    return PreparedDelivery(provider, model, target, wire, headers, binding, history_included)

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None

def open_request(request):
    return urllib.request.build_opener(NoRedirect()).open(request, timeout=45)

def send(services, plan, approval, message, lang):
    if not plan.external:
        return {'reply': services.generate_local_knowledge_reply(message, lang),
                'provider': plan.provider, 'model': plan.model, 'externalDelivery': False}
    if not isinstance(approval, ApprovedDelivery) or not hmac.compare_digest(approval.binding, plan.binding):
        raise AiDeliveryError()
    try:
        request = services.ai_request(plan.target, plan.payload, plan.headers)
        with services.open_ai_request(request) as response:
            raw = response.read(262145)
            if len(raw) > 262144:
                raise ValueError('AI response limit')
            data = json.loads(raw)
        if plan.provider == 'antigravity':
            reply = data.get('candidates', [{}])[0].get('content', {}).get('parts', [{}])[0].get('text', '')
        else:
            reply = data.get('choices', [{}])[0].get('message', {}).get('content', '')
        if not isinstance(reply, str) or not reply.strip():
            raise ValueError('Invalid AI response')
        return {'reply': reply, 'provider': plan.provider, 'model': plan.model,
                'externalDelivery': True, 'providerErasureConfirmed': False}
    except Exception:
        # A timeout/failure does not prove the provider received nothing. Never retry automatically.
        return {'reply': '⚠️ Nie udało się połączyć z dostawcą AI.\n\n'
                        + services.generate_local_knowledge_reply(message, lang),
                'provider': plan.provider, 'model': plan.model, 'error': 'AI_UPSTREAM_UNAVAILABLE',
                'externalDelivery': 'unconfirmed', 'providerErasureConfirmed': False}
