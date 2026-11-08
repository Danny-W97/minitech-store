-- Redis 7. One snapshot protects every existing group and all declared recipients.
local function less(a,b)
  local am,as=string.match(a,'^(%d+)%-(%d+)$')
  local bm,bs=string.match(b,'^(%d+)%-(%d+)$')
  if not am or not bm then error('INVALID_STREAM_FRONTIER') end
  if #am~=#bm then return #am<#bm end
  if am~=bm then return am<bm end
  if #as~=#bs then return #as<#bs end
  return as<bs
end
local function map(raw)
  local result={};for i=1,#raw,2 do result[raw[i]]=raw[i+1] end;return result
end
local key=KEYS[1]
local ageCutoff=ARGV[1]
local deleting=ARGV[2]=='1'
local allowNoGroups=ARGV[3]=='1'
local limit=tonumber(ARGV[4])
local maxGroups=32
local kind=redis.call('TYPE',key).ok
if kind=='none' then return {0,0,'',0,1,0,0} end
if kind~='stream' then return redis.error_reply('INVOICE_RETENTION_WRONG_TYPE') end
local info=map(redis.call('XINFO','STREAM',key))
local groupCount=info.groups
if type(groupCount)~='number' then return redis.error_reply('INVOICE_RETENTION_UNKNOWN_GROUP_COUNT') end
local cutoff=ageCutoff
local blocked=0
if groupCount>maxGroups then
  blocked=4
else
  local groups=redis.call('XINFO','GROUPS',key)
  local seen={}
  for _,raw in ipairs(groups) do
    local g=map(raw);seen[g.name]=true
    if less(g['last-delivered-id'],cutoff) then cutoff=g['last-delivered-id'] end
    local pending=redis.call('XPENDING',key,g.name)
    if pending[1]>0 and less(pending[2],cutoff) then cutoff=pending[2] end
  end
  for i=5,#ARGV do if not seen[ARGV[i]] then blocked=2 end end
  if blocked==0 and #ARGV==4 and not allowNoGroups then blocked=3 end
end
local removed=0
if deleting and blocked==0 and cutoff~='0-0' then
  -- Exact ID selection gives a hard record budget, even with non-default macro-node sizes.
  local rows=redis.call('XRANGE',key,'-','('..cutoff,'COUNT',limit)
  local ids={};for _,row in ipairs(rows) do ids[#ids+1]=row[1] end
  if #ids>0 then removed=redis.call('XDEL',key,unpack(ids)) end
end
local first=redis.call('XRANGE',key,'-','+','COUNT',1)
local oldest='';local eligible=0;local old=0
if #first>0 then
  oldest=first[1][1]
  if less(oldest,ageCutoff) then old=1 end
  if blocked==0 and less(oldest,cutoff) then eligible=1 end
  if blocked==0 and old==1 and eligible==0 then blocked=5 end
end
return {removed,redis.call('XLEN',key),oldest,eligible,blocked,groupCount,old}
