local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

-- Retrieve current bucket state
local data = redis.call('HMGET', key, 'tokens', 'last_refill_ts')
local tokens = tonumber(data[1])
local last_refill_ts = tonumber(data[2])

-- Initialize if key does not exist
if not tokens then
    tokens = capacity
    last_refill_ts = now
else
    -- Replenish tokens based on elapsed time
    local elapsed = math.max(0, now - last_refill_ts)
    tokens = math.min(capacity, tokens + (elapsed * refill_rate))
end
local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end
redis.call('HSET', key, 'tokens', tokens, 'last_refill_ts', now)
redis.call('EXPIRE', key, math.ceil(capacity / refill_rate)) -- Auto-clean old keys
return allowed