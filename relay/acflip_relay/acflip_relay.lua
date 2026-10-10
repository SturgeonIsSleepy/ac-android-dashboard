-- ACFlip read-only CSP race context, no physics or race-rule changes.
local output = ac.getFolder(ac.FolderID.ScriptOrigin) .. '/race-context.txt'
local elapsed, busy = 1, false
local mirror = require('mirror')

function script.update(dt)
  mirror.update(dt)
  elapsed = elapsed + dt
  if elapsed < 0.05 or busy then return end
  elapsed = 0
  local sim, own = ac.getSim(), ac.getCar(0)
  if not own then return end
  local n = math.min(64, #own.bestSplits)
  local personal, world = {}, {}
  for s = 0, n - 1 do
    personal[s + 1] = math.max(0, own.bestSplits[s])
    world[s + 1] = 0
  end
  local count = 0
  for i = 0, sim.carsCount - 1 do
    local car = ac.getCar(i)
    if car and car.isConnected then
      count = count + 1
      for s = 0, math.min(n, #car.bestSplits) - 1 do
        local time = car.bestSplits[s]
        if time > 0 and (world[s + 1] == 0 or time < world[s + 1]) then world[s + 1] = time end
      end
    end
  end
  local payload = table.concat({
    'version=1', 'car=' .. ac.getCarID(0), 'track=' .. ac.getTrackID(),
    'laps=' .. own.lapCount, 'sector=' .. own.currentSector,
    'cars=' .. math.max(1, count), 'limit=' .. sim.pitsSpeedLimit,
    'count=' .. n, 'personal=' .. table.concat(personal, ','),
    'world=' .. table.concat(world, ','), 'grip=' .. sim.roadGrip,
    'wetness=' .. sim.rainWetness, 'water=' .. sim.rainWater,
    'fuelPerLap=' .. own.fuelPerLap, 'end=ACFLIP1', ''
  }, '\n')
  busy = true
  io.saveAsync(output, payload, function(err)
    busy = false
    if err then ac.warn('ACFlip context: ' .. tostring(err)) end
  end)
end
