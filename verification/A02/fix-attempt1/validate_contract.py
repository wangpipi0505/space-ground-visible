"""标准库核验35活动路由、引用、源字段与A01实际结构；只读取证据，不重跑业务。"""
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(Path(__file__).parent))
import build_contract as contract

doc=json.loads((ROOT/'docs/openapi/openapi.json').read_text(encoding='utf-8'))
schemas=doc['components']['schemas']
errors=[]
checks={'refs':0,'responseSamples':0,'requestSamples':0,'valueChecks':0,'markdownLinks':0,'jsonFences':0,'sourceClasses':0,'sourceFields':0,'binaryOrEmptyResponses':0,'shaResponses':0,'schemaObjects':0,'defaults':0,'enumValues':0,'nullableBranches':0,'markdownAnchors':0}

def resolve(schema):
    if '$ref' in schema:
        return schemas[schema['$ref'].rsplit('/',1)[1]]
    return schema

def select_media(content, content_type):
    """按实际响应头选择合同：具体类型优先，其次主类型范围，最后 */*；参数不改变媒体类型。"""
    actual=(content_type or '').split(';',1)[0].strip().lower()
    if '/' not in actual: return None,None
    for candidate in [actual,actual.split('/',1)[0]+'/*','*/*']:
        for declared,media in content.items():
            if declared.lower()==candidate: return declared,media
    return None,None

def published_properties(schema, published):
    """展开交付文件的引用和 allOf 继承；不访问生成器内存的 SCHEMAS/properties。"""
    if '$ref' in schema: return published_properties(published[schema['$ref'].rsplit('/',1)[1]],published)
    result=set(schema.get('properties',{}))
    for part in schema.get('allOf',[]): result.update(published_properties(part,published))
    return result

def source_properties(owner):
    """公开源字段独立展开 Java 父模型，保留 PayloadDef 的非序列化 standard 排除规则。"""
    info=contract.CLASSES[owner]
    result=set(info['fields'])-({'standard'} if owner=='PayloadDef' else set())
    if info['parent'] in contract.CLASSES: result.update(source_properties(info['parent']))
    return result

def source_field_errors(published):
    """检查实际加载的 OpenAPI；删去未出现在样例中的字段也必须可检出。"""
    mismatches=[]
    for owner in contract.CLASSES:
        if owner in contract.skip: continue
        actual=source_properties(owner)
        if owner not in published:
            mismatches.append('源类型缺失 '+owner)
            continue
        exposed=published_properties(published[owner],published)
        if actual!=exposed: mismatches.append('源字段不一致 '+owner+' '+repr(sorted(actual^exposed)))
    return mismatches

def validate(value,schema,path,strict=True):
    checks['valueChecks']+=1
    s=resolve(schema)
    if 'allOf' in s:
        for part in s['allOf']: validate(value,part,path,False)
        return
    if 'anyOf' in s:
        original=len(errors)
        for part in s['anyOf']:
            validate(value,part,path,strict)
            if len(errors)==original: return
            del errors[original:]
        errors.append(path+': 不满足anyOf')
        return
    if value is None:
        if not s.get('nullable') or 'type' not in s or ('enum' in s and None not in s['enum']):
            errors.append(path+': 规范nullable/type/enum不允许null')
        return
    kind=s.get('type')
    ok={'object':isinstance(value,dict),'array':isinstance(value,list),'string':isinstance(value,str),'integer':isinstance(value,int) and not isinstance(value,bool),'number':isinstance(value,(int,float)) and not isinstance(value,bool),'boolean':isinstance(value,bool)}.get(kind,False)
    if not ok:
        errors.append(path+': '+str(kind)+' 收到 '+type(value).__name__)
        return
    if 'enum' in s and value not in s['enum']: errors.append(path+': 枚举不匹配 '+repr(value))
    if kind=='string' and s.get('pattern') and not re.search(s['pattern'],value): errors.append(path+': pattern不匹配')
    if kind=='string' and len(value)>s.get('maxLength',float('inf')): errors.append(path+': 超过maxLength')
    if kind=='string' and s.get('format') in ['date','date-time']:
        try:
            if s['format']=='date': datetime.strptime(value,'%Y-%m-%d')
            else:
                parsed=datetime.fromisoformat(value.replace('Z','+00:00'))
                if parsed.tzinfo is None: errors.append(path+': date-time缺时区')
        except ValueError: errors.append(path+': format不匹配')
    if kind=='object':
        props=s.get('properties',{})
        for req in s.get('required',[]):
            if req not in value: errors.append(path+'.'+req+': 缺必填字段')
        for key,item in value.items():
            if key in props: validate(item,props[key],path+'.'+key)
            elif isinstance(s.get('additionalProperties'),dict): validate(item,s['additionalProperties'],path+'.'+key)
            elif strict and key not in {'standard'}: errors.append(path+'.'+key+': schema缺真实字段')
    elif kind=='array':
        if len(value)<s.get('minItems',0): errors.append(path+': 小于minItems')
        for i,item in enumerate(value): validate(item,s['items'],path+'['+str(i)+']')
    elif kind in ['number','integer']:
        if value<s.get('minimum',-float('inf')) or value>s.get('maximum',float('inf')): errors.append(path+': 数值范围不匹配')

def refs(value):
    if isinstance(value,dict):
        if '$ref' in value:
            checks['refs']+=1
            target=value['$ref']
            if not target.startswith('#/components/schemas/') or target.rsplit('/',1)[1] not in schemas: errors.append('无法解析引用 '+target)
        for child in value.values(): refs(child)
    elif isinstance(value,list):
        for child in value: refs(child)
refs(doc)

def schema_rules(s,path):
    checks['schemaObjects']+=1
    if '$ref' in s:
        if set(s)!={'$ref'}: errors.append(path+': ReferenceObject兄弟键会被忽略')
        return
    if s.get('nullable'):
        checks['nullableBranches']+=1
        if 'type' not in s: errors.append(path+': nullable缺同层type')
    if 'default' in s:
        checks['defaults']+=1
        if 'type' not in s: errors.append(path+': default缺同层type')
        validate(s['default'],s,path+'.default')
    for i,value in enumerate(s.get('enum',[])):
        checks['enumValues']+=1
        validate(value,s,path+'.enum['+str(i)+']')
    if s.get('type')=='array' and 'items' not in s: errors.append(path+': array缺items')
    if 'required' in s:
        if not set(s['required']).issubset(s.get('properties',{})): errors.append(path+': required不存在于properties')
        if len(s['required'])!=len(set(s['required'])): errors.append(path+': required重复')
    for name,child in s.get('properties',{}).items(): schema_rules(child,path+'.'+name)
    if isinstance(s.get('items'),dict): schema_rules(s['items'],path+'[]')
    if isinstance(s.get('additionalProperties'),dict): schema_rules(s['additionalProperties'],path+'.*')
    for key in ['allOf','anyOf','oneOf']:
        for i,child in enumerate(s.get(key,[])): schema_rules(child,path+'.'+key+'['+str(i)+']')

for name,s in schemas.items(): schema_rules(s,name)
for path,ops in doc['paths'].items():
    for method,op in ops.items():
        for p in op.get('parameters',[]): schema_rules(p['schema'],method+' '+path+'.'+p['name'])

# 活动控制器独立按映射注解抽取，继承的原health单独补入；不计未扫描旧controller。
active=set()
controllers=list((contract.SOURCE/'com/runisys/orbit/spacevisibility/api').rglob('*Controller.java'))
for source in controllers:
    text=source.read_text(encoding='utf-8')
    prefix=re.search(r'@RequestMapping\("([^\"]+)"\)',text)
    if not prefix: continue
    for mapping in re.finditer(r'@(Get|Post|Put|Delete)Mapping(?:\(([\s\S]*?)\))?\s*',text):
        argument=mapping.group(2) or ''
        suffix=re.search(r'"([^\"]+)"',argument)
        active.add(mapping.group(1).upper()+' '+prefix.group(1)+(suffix.group(1) if suffix else ''))
    if 'extends OrbitVisibilityController' in text: active.add('GET '+prefix.group(1)+'/health')
declared={m.upper()+' '+p for p,ops in doc['paths'].items() for m in ops}
a01={r['route'] for r in contract.summary['routeMatrix']}
if len(declared)!=35 or declared!=active or declared!=a01: errors.append('路由不一致 '+repr({'declared':len(declared),'controller':len(active),'a01':len(a01),'diff':sorted(declared^active),'diffA01':sorted(declared^a01)}))
if doc['openapi']!='3.0.3': errors.append('OpenAPI版本不符')

errors.extend(source_field_errors(schemas))
for owner,info in contract.CLASSES.items():
    if owner in contract.skip: continue
    checks['sourceClasses']+=1
    checks['sourceFields']+=len(source_properties(owner))

for method,path,title,req,res in contract.ROUTES:
    operation=doc['paths'][path][method.lower()]
    for event in (e for e in contract.EVENTS if e['route']==method+' '+path):
        example=contract.load_event(event)
        event=example['event']
        p=ROOT/example['responseSource']
        if hashlib.sha256(p.read_bytes()).hexdigest()!=event['sha256']: errors.append('原字节SHA不匹配 '+str(p))
        checks['shaResponses']+=1
        content=operation['responses'][str(event['status'])].get('content',{})
        media=None
        if event['bytes']:
            matched,media=select_media(content,event.get('headers',{}).get('Content-Type'))
            if media is None: errors.append(method+' '+path+': 实际 Content-Type 无匹配合同 '+str(event.get('headers',{}).get('Content-Type')))
        if example['response'] is not None:
            if media is not None: validate(example['response'],media['schema'],method+' '+path+' '+str(event['status']))
            checks['responseSamples']+=1
        else: checks['binaryOrEmptyResponses']+=1
        if event['status']<300 and req and req!='MultipartUpload' and example['request'] is not None:
            validate(example['request'],next(iter(operation['requestBody']['content'].values()))['schema'],method+' '+path+' request')
            checks['requestSamples']+=1

for filename in ['docs/接口文档.md','README.md','verification/A02/验证报告.md']:
    file=ROOT/filename
    if not file.exists(): continue
    text=file.read_text(encoding='utf-8')
    for target in re.findall(r'\[[^\]]+\]\(([^)]+)\)',text):
        if '://' in target: continue
        location,_,anchor=target.partition('#')
        linked=(file.parent/location).resolve() if location else file
        if not linked.exists(): errors.append('Markdown链接不存在 '+filename+' '+target)
        elif anchor and linked.is_file():
            headings=re.findall(r'^#+\s+(.+)$',linked.read_text(encoding='utf-8'),re.M)
            slugs={re.sub(r'[^\w\s-]','',h).strip().lower().replace(' ','-') for h in headings}
            if anchor not in slugs: errors.append('Markdown锚点不存在 '+filename+' '+target)
            checks['markdownAnchors']+=1
        checks['markdownLinks']+=1
    if text.count('```')%2: errors.append('Markdown代码围栏未闭合 '+filename)
    for block in re.findall(r'```json\s*\n([\s\S]*?)\n```',text):
        try: json.loads(block); checks['jsonFences']+=1
        except ValueError as ex: errors.append('JSON示例语法无效 '+str(ex))

def field_count(s):
    count=len(s.get('properties',{}))
    for child in s.get('properties',{}).values(): count+=field_count(child)
    for key in ['allOf','anyOf','oneOf']:
        count+=sum(field_count(x) for x in s.get(key,[]))
    if isinstance(s.get('items'),dict): count+=field_count(s['items'])
    return count

result={'time':datetime.now(timezone.utc).isoformat(),'passed':not errors,'operations':len(declared),'controllerOperations':len(active),'a01Operations':len(a01),'schemas':len(schemas),'topLevelProperties':sum(len(s.get('properties',s.get('allOf',[{}])[-1].get('properties',{}))) for s in schemas.values()),'properties':sum(field_count(s) for s in schemas.values()),'checks':checks,'errors':errors}
contract.dump(ROOT/'verification/A02/validation-result.json',result)
print(json.dumps(result,ensure_ascii=False,indent=2))
sys.exit(0 if not errors else 1)
