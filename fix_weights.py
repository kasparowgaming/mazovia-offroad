import os
import re

dir_path = r'routing\src\main\java\pl\mazovia\offroad\routing\engine\weights'
files = ['OffRoadWeights.kt', 'AndroidWeightingFactory.kt', 'AndroidCustomWeighting.kt']

for fname in files:
    path = os.path.join(dir_path, fname)
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
        
    content = content.replace('package com.mazoviaoffroad.app.data.routing', 'package pl.mazovia.offroad.routing.engine.weights')
    content = content.replace('com.mazoviaoffroad.app.domain.model.RoutingMode', 'pl.mazovia.offroad.domain.model.RoutingProfile')
    content = content.replace('RoutingMode.NORMAL', 'RoutingProfile.BEZPIECZNY')
    content = content.replace('RoutingMode.MAX_OFF_ROAD', 'RoutingProfile.TERENOWY')
    content = content.replace('RoutingMode.EXTREME', 'RoutingProfile.ODKRYWCZY')
    content = content.replace('RoutingMode', 'RoutingProfile')
    
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)
