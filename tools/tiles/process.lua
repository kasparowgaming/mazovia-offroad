-- Mazovia Offroad custom process.lua

function init_function()
end

function exit_function()
end

function attribute_function(attr, layer)
    return attr
end

function node_function()
    local place = Find("place")
    if place ~= "" then
        Layer("place", false)
        Attribute("class", place)
        Attribute("name", Find("name"))
    end
end

function way_function()
    local highway = Find("highway")
    if highway ~= "" then
        Layer("transportation", false)
        Attribute("highway", highway)
        
        local surface = Find("surface")
        if surface ~= "" then Attribute("surface", surface) end
        
        local tracktype = Find("tracktype")
        if tracktype ~= "" then Attribute("tracktype", tracktype) end
        
        local access = Find("access")
        if access ~= "" then Attribute("access", access) end
        
        local motor_vehicle = Find("motor_vehicle")
        if motor_vehicle ~= "" then Attribute("motor_vehicle", motor_vehicle) end
        
        local motorcycle = Find("motorcycle")
        if motorcycle ~= "" then Attribute("motorcycle", motorcycle) end
        
        local ford = Find("ford")
        if ford ~= "" then Attribute("ford", ford) end
        
        local bridge = Find("bridge")
        if bridge ~= "" then Attribute("bridge", bridge) end
        
        local tunnel = Find("tunnel")
        if tunnel ~= "" then Attribute("tunnel", tunnel) end
        
        local layer = Find("layer")
        if layer ~= "" then AttributeNumeric("layer", tonumber(layer) or 0) end
        
        local name = Find("name")
        if name ~= "" then Attribute("name", name) end
        
        local ref = Find("ref")
        if ref ~= "" then Attribute("ref", ref) end
        
        local smoothness = Find("smoothness")
        if smoothness ~= "" then Attribute("smoothness", smoothness) end
        
        local sac_scale = Find("sac_scale")
        if sac_scale ~= "" then Attribute("sac_scale", sac_scale) end

        local service = Find("service")
        if service ~= "" then Attribute("service", service) end
        
        return
    end

    local natural = Find("natural")
    local landuse = Find("landuse")
    
    if natural == "wood" or landuse == "forest" then
        Layer("landcover", true)
        Attribute("class", "forest")
        return
    end
    
    if natural == "sand" then
        Layer("landcover", true)
        Attribute("class", "sand")
        return
    end
    
    if natural == "water" or landuse == "reservoir" then
        Layer("water", true)
        Attribute("class", natural == "water" and "water" or "reservoir")
        return
    end
    
    local waterway = Find("waterway")
    if waterway ~= "" then
        Layer("waterway", false)
        Attribute("class", waterway)
        return
    end
    
    local building = Find("building")
    if building ~= "" and building ~= "no" then
        Layer("building", true)
        return
    end
end
