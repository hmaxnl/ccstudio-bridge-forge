local function main()
	fs.makeDir("/programs")

  print("Downloading ccLink client for CraftOS...")

	local response, status, errResponse = http.get({
		url = "https://gitlab.com/api/v4/projects/46294110/releases/permalink/latest/downloads/out/ccLink.lua",
		method = "GET",
		redirect = true,
	})

	if not response then
		if errResponse then
			local code, statusText = errResponse.getResponseCode()
			errResponse.close()
			error(string.format("Download failed: %d %s", code, statusText or status))
			return
		end

		error("Download failed: " .. status)
		return
	end

	local content = response.readAll()
	response.close()

	local file, err = fs.open("/programs/ccLink.lua", "w")
	if not file then
		error(err)
		return
	end

	file.write(content)
	file.close()

	local shellPath = shell.path()
	local inPath = false

	for p in string.gmatch(shellPath, "[^:]+") do
		if p == "/programs" then
			inPath = true
			break
		end
	end

	if not inPath then
		shell.setPath(shellPath .. ":/programs")
	end

	settings.set("ccLink.host", "$HOST")
	settings.set("ccLink.uuid", "$UUID")
	settings.save()

	term.setTextColor(colors.green)	

  if multishell then
    local ccRequire = require('cc.require')
    local env = setmetatable({ shell = shell, multishell = multishell }, { __index = _ENV })
    env.require, env.package = ccRequire.make(env, "/rom/programs")
    multishell.launch(env, "/programs/ccLink.lua")
    print("ccLink is installed and started in the background!")
  else
    print("ccLink is now installed!")
  end
end

local success, err = pcall(main)

if not success then
	printError(err)
end
