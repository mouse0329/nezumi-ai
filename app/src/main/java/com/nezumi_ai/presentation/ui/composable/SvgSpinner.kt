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

private const val PATH1_DATA = "M163.5291,140.84564z"
private const val PATH2_DATA = "M310.97494,183.39669c0,27.60886 -31.82369,49.99027 -71.08019,49.99027c-39.25649,0 -71.08019,-22.3814 -71.08019,-49.99027c0,-27.60886 31.82369,-49.99027 71.08019,-49.99027c39.25649,0 71.08019,22.3814 71.08019,49.99027z"
private const val PATH3_DATA = "M204.00263,193.50827c0,-2.60227 2.10956,-4.71183 4.71183,-4.71183c2.60227,0 4.71183,2.10956 4.71183,4.71183c0,2.60227 -2.10956,4.71183 -4.71183,4.71183c-2.60227,0 -4.71183,-2.10956 -4.71183,-4.71183z"
private const val PATH4_DATA = "M325.56462,120.08877l-25.32618,32.51781"
private const val PATH5_DATA = "M173.43332,149.01896c0,-17.43189 14.13134,-31.56323 31.56323,-31.56323c17.43189,0 31.56323,14.13134 31.56323,31.56323c0,17.43189 -14.13134,31.56323 -31.56323,31.56323c-17.43189,0 -31.56323,-14.13134 -31.56323,-31.56323z"
private const val PATH6_DATA = "M193.80959,149.4185c0,-5.29576 4.29307,-9.58883 9.58883,-9.58883c5.29576,0 9.58883,4.29307 9.58883,9.58883c0,5.29576 -4.29307,9.58883 -9.58883,9.58883c-5.29576,0 -9.58883,-4.29307 -9.58883,-9.58883z"

private const val SVG_BASE_WIDTH = 166.25f
private const val SVG_BASE_HEIGHT = 128.43f

@Composable
fun SvgSpinner(modifier: Modifier = Modifier) {
    val path1 = remember { PathParser().parsePathString(PATH1_DATA).toPath() }
    val path2 = remember { PathParser().parsePathString(PATH2_DATA).toPath() }
    val path3 = remember { PathParser().parsePathString(PATH3_DATA).toPath() }
    val path4 = remember { PathParser().parsePathString(PATH4_DATA).toPath() }
    val path5 = remember { PathParser().parsePathString(PATH5_DATA).toPath() }
    val path6 = remember { PathParser().parsePathString(PATH6_DATA).toPath() }

    val infiniteTransition = rememberInfiniteTransition(label = "spinner")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 700,
                easing = FastOutSlowInEasing
            )
        ),
        label = "progress"
    )

    val path2Length = 383.2f
    val dashLength = 240f 

    Box(modifier = modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scaleX = size.width / SVG_BASE_WIDTH
            val scaleY = size.height / SVG_BASE_HEIGHT
            
            drawContext.canvas.save()
            drawContext.canvas.scale(scaleX, scaleY)
            drawContext.canvas.translate(-162.56f, -111.21f)

            // 共通のアニメーションパスエフェクト
            val dashEffect = PathEffect.dashPathEffect(
                intervals = floatArrayOf(dashLength, path2Length - dashLength),
                phase = path2Length * (1f - progress) * 2f
            )

            // =========================================================
            // STEP 1: すべての「白い縁取り（下層）」を先にまとめて描画
            // =========================================================
            
            // path2（メインの動く楕円）の白縁
            drawPath(
                path = path2,
                color = Color.White,
                style = Stroke(width = 18.5f, cap = StrokeCap.Round, pathEffect = dashEffect)
            )
            // path4（直線）の白縁
            drawPath(
                path = path4,
                color = Color.White,
                style = Stroke(width = 12.5f, cap = StrokeCap.Round)
            )
            // path5（丸）の白縁
            drawPath(
                path = path5,
                color = Color.White,
                style = Stroke(width = 18.5f)
            )

            // =========================================================
            // STEP 2: 本体のカラー・黒線（上層）を重ねて描画
            // =========================================================
            
            // path1
            drawPath(path = path1, color = Color(0xFFE900FF))
            drawPath(path = path1, color = Color.Black, style = Stroke(width = 2.5f))

            // path2 本体
            drawPath(path = path2, color = Color(0xFFAFAFAF))
            drawPath(
                path = path2,
                color = Color.Black,
                style = Stroke(width = 12.5f, cap = StrokeCap.Round, pathEffect = dashEffect)
            )

            // path3
            drawPath(path = path3, color = Color.Black)
            drawPath(path = path3, color = Color.Black, style = Stroke(width = 2f))
            
            // path4 本体
            drawPath(
                path = path4,
                color = Color.Black,
                style = Stroke(width = 6.5f, cap = StrokeCap.Round)
            )
            
            // path5 本体
            drawPath(path = path5, color = Color(0xFFAFAFAF))
            drawPath(path = path5, color = Color.Black, style = Stroke(width = 12.5f))
            
            // path6
            drawPath(path = path6, color = Color(0xFFED00C2))

            drawContext.canvas.restore()
        }
    }
}