-- Independent rear views anchored to the car's actual mirror meshes.
local mirror = {}
local folder = ac.getFolder(ac.FolderID.ScriptOrigin)
local resolution = vec2(1024,576)
local renderResolution = vec2(1280,720)
local shot, canvas, output, elapsed, activeView = nil,nil,nil,1,0
local mirrors, carID, lastError = {},'', ''
local enhance = {
  textures = {}, values = {step = vec2(1/1280,1/720)},
  shader = [[
    float4 main(PS_IN pin) {
      float2 uv = pin.Tex;
      float3 c = txInput.SampleLevel(samLinearClamp,uv,0).rgb;
      float3 nearby = (txInput.SampleLevel(samLinearClamp,uv+float2(step.x,0),0).rgb
        + txInput.SampleLevel(samLinearClamp,uv-float2(step.x,0),0).rgb
        + txInput.SampleLevel(samLinearClamp,uv+float2(0,step.y),0).rgb
        + txInput.SampleLevel(samLinearClamp,uv-float2(0,step.y),0).rgb)*0.25;
      c += clamp((c-nearby)*0.20,-0.025,0.025);
      return float4(saturate((c-0.45)*1.22+0.45),1);
    }
  ]]
}

local function findMirrors(car,right)
  mirrors = {}
  local config = ac.INIConfig.carData(0,'mirrors.ini')
  local root = ac.findNodes('carRoot:0')
  for i = 0,31 do
    local name = config:get('MIRROR_' .. i,'NAME','')
    if name ~= '' then
      local mesh = root:findMeshes(name)
      local a,b,count = mesh:getLocalAABB()
      local parent = mesh:getParent()
      local transform = parent:getWorldTransformationRaw()
      if count > 0 and transform then
        local center = (a+b)*0.5
        local side = (transform:transformPoint(center)-car.position):dot(right)
        local view = math.abs(side) < car.aabbSize.x*0.2 and 2 or side < 0 and 1 or 3
        mirrors[view] = {parent=parent,center=center,name=name}
      end
    end
  end
  carID = ac.getCarID(0)
end

local function release()
  if shot then shot:dispose(); shot = nil end
  if canvas then canvas:dispose(); canvas = nil end
  if output then output:dispose(); output = nil end
  activeView = 0
end

local function capture(which)
  local car = ac.getCar(0)
  local up, forward = car.up:clone(),car.look:clone()
  local right = forward:clone():cross(up):normalize()
  if carID ~= ac.getCarID(0) then findMirrors(car,right) end
  local piece = mirrors[which]
  if not piece then error('This car has no requested mirror mesh') end
  if activeView ~= which then
    release()
    local scene
    if which == 2 then
      -- Keep other cars; omit the player's cockpit so roll bars cannot hide traffic.
      scene = ac.findNodes('trackRoot:yes')
      for i = 1,ac.getSim().carsCount-1 do scene:append(ac.findNodes('carRoot:' .. i)) end
    else scene = ac.findNodes('sceneRoot:yes') end
    shot = ac.GeometryShot(scene,renderResolution,1,true,render.AntialiasingMode.None,render.TextureFormat.R16G16B16A16.Float)
    shot:setOriginalLighting(true):setSky(true):setTransparentPass(true):setMaxLayer(3):setShadersType(render.ShadersType.SimplifiedWithLights)
    shot:setClippingPlanes(0.03,1000)
    canvas,output = ui.ExtraCanvas(renderResolution),ui.ExtraCanvas(resolution)
    enhance.textures.txInput = canvas
    activeView = which
  end
  local side = which == 1 and -1 or which == 3 and 1 or 0
  local options = {50,side*10.2,0,0,0,0}
  local saved = '\n' .. (io.load(folder .. '/mirror-config.txt') or '')
  local row = saved:match('\n' .. which .. '=([^\r\n]+)')
  if row then
    local field = 1
    for value in row:gmatch('[^,]+') do
      if field > 6 then break end
      options[field] = tonumber(value) or options[field]
      field = field+1
    end
  end
  local pos = piece.parent:getWorldTransformationRaw():transformPoint(piece.center)
  -- Move the side view back/out slightly so a near-rearward view keeps a small body reference.
  pos = pos-forward*(side == 0 and 0.06 or car.aabbSize.z*0.26)+right*(side*0.26)
  pos = pos+right*options[4]+up*options[5]+forward*options[6]
  local yaw,pitch = math.rad(options[2]),math.rad(options[3])
  local look = (-forward*math.cos(yaw)+right*math.sin(yaw))*math.cos(pitch)+up*math.sin(pitch)
  shot:update(pos,look,up,options[1])
  canvas:clear()
  canvas:update(function()
    ui.beginTonemapping()
    ui.setShadingOffset(1,0,1,1)
    ui.drawImage(shot,vec2(),renderResolution,rgbm.colors.white,vec2(1,0),vec2(0,1))
    ui.resetShadingOffset()
    ui.endTonemapping(0,0,true)
  end)
  if not output:updateWithShader(enhance) then return end
  local temporary = folder .. '/mirror-next.jpg'
  output:save(temporary,ac.ImageFormat.JPG)
  if not io.move(temporary,folder .. '/mirror-' .. which .. '.jpg',true) then error('Mirror image could not be published') end
  lastError = ''
  io.save(folder .. '/mirror-status.txt','ready=' .. which .. '\nmesh=' .. piece.name .. '\nposition=' .. tostring(pos) .. '\nlook=' .. tostring(look) .. '\nfov=' .. options[1] .. '\nsettings=' .. table.concat(options,',') .. '\nsize=1024,576')
end

function mirror.update(dt)
  elapsed = elapsed+dt
  if elapsed < 0.05 then return end
  elapsed = 0
  local command = io.load(folder .. '/mirror-request.txt') or ''
  local selected,stamp = command:match('^(%d+),(%d+)')
  selected,stamp = tonumber(selected) or 0,tonumber(stamp) or 0
  if os.time()-stamp > 3 then selected = 0 end
  if selected < 1 or selected > 3 then release(); return end
  local ok,err = pcall(capture,selected)
  if not ok and tostring(err) ~= lastError then
    lastError = tostring(err)
    io.save(folder .. '/mirror-status.txt','error=' .. lastError)
    ac.warn('ACFlip mirror: ' .. lastError)
  end
end
return mirror
