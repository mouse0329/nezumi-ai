package com.nezumi_ai.presentation.ui.composable

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 線を長くし、速度を大幅に上げたJetpack Compose用ロードアニメーション
 */
@Composable
fun SvgSpinner(modifier: Modifier = Modifier) {
    val path1Data = "M163.5291,140.84564z"
    val path2Data = "M310.97494,183.39669c0,27.60886 -31.82369,49.99027 -71.08019,49.99027c-39.25649,0 -71.08019,-22.3814 -71.08019,-49.99027c0,-27.60886 31.82369,-49.99027 71.08019,-49.99027c39.25649,0 71.08019,22.3814 71.08019,49.99027z"
    val path3Data = "M204.00263,193.50827c0,-2.60227 2.10956,-4.71183 4.71183,-4.71183c2.60227,0 4.71183,2.10956 4.71183,4.71183c0,2.60227 -2.10956,4.71183 -4.71183,4.71183c-2.60227,0 -4.71183,-2.10956 -4.71183,-4.71183z"
    val path4Data = "M325.56462,120.08877l-25.32618,32.51781"
    val path5Data = "M173.43332,149.01896c0,-17.43189 14.13134,-31.56323 31.56323,-31.56323c17.43189,0 31.56323,14.13134 31.56323,31.56323c0,17.43189 -14.13134,31.56323 -31.56323,31.56323c-17.43189,0 -31.56323,-14.13134 -31.56323,-31.56323z"
    val path6Data = "M193.80959,149.4185c0,-5.29576 4.29307,-9.58883 9.58883,-9.58883c5.29576,0 9.58883,4.29307 9.58883,9.58883c0,5.29576 -4.29307,9.58883 -9.58883,9.58883c-5.29576,0 -9.58883,-4.29307 -9.58883,-9.58883z"

    val path1 = remember { PathParser().parsePathString(path1Data).toPath() }
    val path2 = remember { PathParser().parsePathString(path2Data).toPath() }
    val path3 = remember { PathParser().parsePathString(path3Data).toPath() }
    val path4 = remember { PathParser().parsePathString(path4Data).toPath() }
    val path5 = remember { PathParser().parsePathString(path5Data).toPath() }
    val path6 = remember { PathParser().parsePathString(path6Data).toPath() }

    // アニメーション設定：周期を0.7秒に短縮して高速化
    val infiniteTransition = rememberInfiniteTransition(label = "spinner")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1000, // 1000ms -> 700ms に短縮（高速化）
                easing = FastOutSlowInEasing
            )
        ),
        label = "progress"
    )

    val path2Length = 383.2f
    val dashLength = 240f // 120f -> 240f に延長（線の長さを約2倍に）

    Box(modifier = modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scaleX = size.width / 166.25f
            val scaleY = size.height / 128.43f
            
            drawContext.canvas.save()
            drawContext.canvas.scale(scaleX, scaleY)
            drawContext.canvas.translate(-162.56f, -111.21f)

            // 重ね順通りに描画
            drawPath(path = path1, color = Color(0xFFE900FF))
            drawPath(path = path1, color = Color.Black, style = Stroke(width = 2.5f))

            // メインの楕円（アニメーション対象）
            drawPath(path = path2, color = Color(0xFFAFAFAF))
            drawPath(
                path = path2,
                color = Color.Black,
                style = Stroke(
                    width = 12.5f,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(
                        intervals = floatArrayOf(dashLength, path2Length - dashLength),
                        phase = path2Length * (1f - progress) * 2f // 1サイクルで2回転分移動させてさらに高速化
                    )
                )
            )

            drawPath(path = path3, color = Color.Black)
            drawPath(path = path3, color = Color.Black, style = Stroke(width = 2f))
            drawPath(path = path4, color = Color.Black, style = Stroke(width = 6.5f, cap = StrokeCap.Round))
            drawPath(path = path5, color = Color(0xFFAFAFAF))
            drawPath(path = path5, color = Color.Black, style = Stroke(width = 12.5f))
            drawPath(path = path6, color = Color(0xFFED00C2))

            drawContext.canvas.restore()
        }
    }
}
