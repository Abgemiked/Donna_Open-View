"""Secret-bearing parent broker, fixed TLS endpoint, atomic per-call budgets."""
import base64
import json
import httpx
from app.evaluation.budget import BudgetStop


class ClaudeBroker:
    def __init__(self, key, model, ledger, *, input_price, output_price, transport=None):
        if not key or not model.startswith('claude-'):raise ValueError('claude_configuration_missing')
        self.key=key;self.model=model;self.ledger=ledger
        self.input_price=input_price;self.output_price=output_price
        self.observation_sink=None
        self.client=httpx.Client(base_url='https://api.anthropic.com',trust_env=False,follow_redirects=False,timeout=60,transport=transport)

    def __call__(self,body):
        if self.observation_sink is None:raise BudgetStop('protected_observation_sink_required')
        if not isinstance(body,dict) or body.get('model')!=self.model or body.get('tools') or not isinstance(body.get('messages'),list):
            raise BudgetStop('provider_request_denied')
        bound=body.get('max_tokens')
        if type(bound) is not int or not 1<=bound<=2048:raise BudgetStop('output_bound_invalid')
        raw=json.dumps(body,ensure_ascii=False).encode('utf-8')
        if len(raw)>100000:raise BudgetStop('input_bound_invalid')
        cid=self.ledger.reserve(len(raw)+2048,bound,input_price=self.input_price,output_price=self.output_price)
        try:
            response=self.client.post('/v1/messages',json=body,headers={'x-api-key':self.key,'anthropic-version':'2023-06-01'})
            if response.status_code!=200:raise BudgetStop('provider_http_failed')
            if self.observation_sink is not None:
                self.observation_sink({'kind':'provider_http_response','model':self.model,'body':base64.b64encode(response.content).decode(),'reservation_id':cid})
            usage,model=parse_usage(response.content)
            if model!=self.model:raise BudgetStop('provider_model_mismatch')
            self.ledger.settle(cid,input_tokens=usage['input_tokens'],output_tokens=usage['output_tokens'],input_price=self.input_price,output_price=self.output_price)
            return {'type':'claude_response','status':200,'body':base64.b64encode(response.content).decode(),
                    'metadata':{'provider':'claude','model':model,**usage,'reservation_id':cid}}
        except Exception:
            self.ledger.stop() # Retain the reservation on unknown usage or interrupted calls.
            raise BudgetStop('provider_call_stopped') from None

    def close(self):self.client.close()


def parse_usage(raw):
    start=None;last=None;completed=False
    for line in raw.decode('utf-8').splitlines():
        if not line.startswith('data:'):continue
        event=json.loads(line[5:])
        if event.get('type')=='error':raise BudgetStop('provider_stream_error')
        if event.get('type')=='message_start':start=event['message']
        if event.get('type')=='message_delta':last=event
        if event.get('type')=='message_stop':completed=True
    if not completed or start is None or last is None or last.get('delta',{}).get('stop_reason')!='end_turn':
        raise BudgetStop('provider_stream_incomplete')
    initial=start.get('usage',{});final=last.get('usage',{})
    keys=('input_tokens','cache_creation_input_tokens','cache_read_input_tokens')
    if 'input_tokens' not in initial or 'output_tokens' not in final:raise BudgetStop('usage_missing')
    values=[initial.get(k,0) for k in keys]+[final['output_tokens']]
    if any(type(n) is not int or n<0 for n in values):raise BudgetStop('usage_invalid')
    return {'input_tokens':sum(values[:3]),'output_tokens':values[3]},start.get('model')
