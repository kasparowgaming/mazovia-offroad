import os

path = r'routing\src\main\java\pl\mazovia\offroad\routing\engine\AndroidGraphHopper.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('package com.mazoviaoffroad.app.data.routing', 'package pl.mazovia.offroad.routing.engine')
content = content.replace('AndroidWeightingFactory(encodingManager)', 'pl.mazovia.offroad.routing.engine.weights.AndroidWeightingFactory(encodingManager)')

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
