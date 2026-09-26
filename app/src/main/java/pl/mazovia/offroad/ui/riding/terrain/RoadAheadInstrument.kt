package pl.mazovia.offroad.ui.riding.terrain

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
@OptIn(ExperimentalTextApi::class)
fun RoadAheadInstrument(model: TerrainInstrumentModel, modifier: Modifier = Modifier) {
    val status = when (model) {
        TerrainInstrumentModel.NoRoute -> "Teren: brak trasy"
        TerrainInstrumentModel.Arrived -> "Teren: cel"
        is TerrainInstrumentModel.Detached -> "Teren: poza trasą"
        is TerrainInstrumentModel.NoData -> "Teren: brak danych"
        is TerrainInstrumentModel.Valid -> "Teren: dane"
    }
    Box(modifier.background(Color(0xFF101B24)).testTag("terrain_instrument")
        .semantics { contentDescription = status }) {
        if (model is TerrainInstrumentModel.Valid) {
            val line = remember { Path() }
            val fill = remember { Path() }
            val textMeasurer = rememberTextMeasurer()
            val distanceLabels = remember(textMeasurer) {
                listOf("+0 m", "+300 m", "+600 m").map {
                    textMeasurer.measure(it, style = TextStyle(color = Color.White, fontSize = 17.sp))
                }
            }
            Canvas(Modifier.fillMaxSize().testTag("terrain_distance")) {
                val left = size.width * .10f
                val right = size.width * .94f
                val top = size.height * .26f
                val bottom = size.height * .78f
                fun x(d: Double) = left + ((d - model.windowStartM) /
                    (model.windowEndM - model.windowStartM)).toFloat() * (right - left)
                fun y(h: Float) = bottom - (h - model.minHeightM) /
                    (model.maxHeightM - model.minHeightM) * (bottom - top)
                drawLine(Color(0xFF66818D), androidx.compose.ui.geometry.Offset(left, bottom),
                    androidx.compose.ui.geometry.Offset(right, bottom), 2.dp.toPx())
                var previous: InstrumentSample? = null
                fun closeRun() {
                    if (previous != null) {
                        fill.lineTo(x(previous!!.distanceM), bottom)
                        fill.close()
                        drawPath(fill, Color(0xFF175866))
                        drawPath(line, Color(0xFF3CE5DA), style = Stroke(3.dp.toPx()))
                    }
                    line.rewind(); fill.rewind(); previous = null
                }
                model.samples.forEach { sample ->
                    val h = sample.heightM
                    if (h == null) { closeRun(); return@forEach }
                    if (previous == null) {
                        line.moveTo(x(sample.distanceM), y(h))
                        fill.moveTo(x(sample.distanceM), bottom)
                        fill.lineTo(x(sample.distanceM), y(h))
                    } else {
                        line.lineTo(x(sample.distanceM), y(h))
                        fill.lineTo(x(sample.distanceM), y(h))
                    }
                    previous = sample
                    if (sample.breakAfter) closeRun()
                }
                closeRun()
                val markerX = x(model.riderM)
                drawLine(Color.White, androidx.compose.ui.geometry.Offset(markerX, top),
                    androidx.compose.ui.geometry.Offset(markerX, bottom), 3.dp.toPx())
                for (index in 0..2) {
                    val tickX = x(model.riderM + index * 300.0)
                    drawLine(Color.White, Offset(tickX, bottom - 5.dp.toPx()),
                        Offset(tickX, bottom + 5.dp.toPx()), 2.dp.toPx())
                    val label = distanceLabels[index]
                    drawText(label, topLeft = Offset(tickX - label.size.width / 2f,
                        bottom + 8.dp.toPx()))
                }
                val arrowX = markerX + 12.dp.toPx()
                val arrowY = top - 14.dp.toPx()
                drawLine(Color.White, Offset(arrowX, arrowY), Offset(arrowX + 24.dp.toPx(), arrowY),
                    3.dp.toPx())
                drawLine(Color.White, Offset(arrowX + 24.dp.toPx(), arrowY),
                    Offset(arrowX + 17.dp.toPx(), arrowY - 7.dp.toPx()), 3.dp.toPx())
                drawLine(Color.White, Offset(arrowX + 24.dp.toPx(), arrowY),
                    Offset(arrowX + 17.dp.toPx(), arrowY + 7.dp.toPx()), 3.dp.toPx())
            }
            Text("NAPRZÓD →", Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("${model.gradeLabel}  ${model.nextEventLabel ?: ""}",
                Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 40.dp)
                    .testTag("terrain_slope"), color = Color.White, fontSize = 22.sp)
            Text("Δ ${(model.maxHeightM - model.minHeightM).toInt()} m",
                Modifier.align(Alignment.CenterStart).padding(start = 8.dp), color = Color.White)
            if (model.showStaleIndicator) Text("POZYCJA NIEAKTUALNA",
                Modifier.align(Alignment.Center), color = Color.Yellow, fontSize = 19.sp)
        } else {
            if (model is TerrainInstrumentModel.Detached && model.samples.any { it.heightM != null }) {
                val greyPath = remember { Path() }
                val heights = remember(model.samples) { model.samples.mapNotNull { it.heightM } }
                val low = heights.min()
                val span = (heights.max() - low).coerceAtLeast(10f)
                val first = model.samples.first().distanceM
                val last = model.samples.last().distanceM
                Canvas(Modifier.fillMaxSize()) {
                    greyPath.rewind()
                    var connected = false
                    model.samples.forEach { sample ->
                        val h = sample.heightM
                        if (h == null) { connected = false; return@forEach }
                        val x = size.width * ((sample.distanceM - first) / (last - first).coerceAtLeast(1.0)).toFloat()
                        val y = size.height * (.78f - (h - low) / span * .5f)
                        if (connected) greyPath.lineTo(x, y) else greyPath.moveTo(x, y)
                        connected = !sample.breakAfter
                    }
                    drawPath(greyPath, Color(0xFF61717A), style = Stroke(3.dp.toPx()))
                }
            }
            val label = when (model) {
                TerrainInstrumentModel.NoRoute -> "BRAK TRASY"
                TerrainInstrumentModel.Arrived -> "CEL"
                is TerrainInstrumentModel.Detached -> "POZA TRASĄ" +
                    (model.distanceToRouteM?.let { " · ${it.toInt()} m" } ?: "")
                is TerrainInstrumentModel.NoData -> "BRAK DANYCH WYSOKOŚCI"
                else -> ""
            }
            Text(label, Modifier.align(Alignment.Center), color = Color.White,
                fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (model is TerrainInstrumentModel.NoData && model.showStaleIndicator) {
                Text("POZYCJA NIEAKTUALNA", Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    color = Color.Yellow)
            }
        }
    }
}
