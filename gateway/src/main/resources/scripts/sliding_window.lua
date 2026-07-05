local rate_limit_key = KEYS[1]

local window_size = tonumber(ARGV[1])
local volume_limit = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

-- Read all fields atomically from a single Hash
local state = redis.call("HMGET", rate_limit_key, "prev_count", "current_count", "window_start")

local prev_count = tonumber(state[1] or "0")
local current_count = tonumber(state[2] or "0")
local current_window_start = tonumber(state[3] or "0")

-- Initialize if nil (first request ever)
if current_window_start == 0 then
    current_window_start = now
end

local elapsed = now - current_window_start

-- Shift window if elapsed time meets or exceeds window size
if elapsed >= window_size then
    prev_count = current_count
    current_count = 0
    current_window_start = now
    elapsed = 0 -- Corrected baseline for the estimation formula below
end

-- Calculate estimated requests using the weight of the previous window
local estimated = (prev_count * (1 - (elapsed / window_size))) + current_count

-- Reject if estimated volume breaches the limit
if estimated >= volume_limit then
    return 0 -- Rejected
end

-- Allow request and increment current count
current_count = current_count + 1

-- Persist all fields inside the single Hash in one operation
redis.call("HSET", rate_limit_key,
    "prev_count", prev_count,
    "current_count", current_count,
    "window_start", current_window_start
)

-- Apply a single TTL to clean up the entire Hash (2x window size)
local ttl = math.ceil(window_size * 2)
redis.call("expire", rate_limit_key, ttl)

return 1 -- Allowed