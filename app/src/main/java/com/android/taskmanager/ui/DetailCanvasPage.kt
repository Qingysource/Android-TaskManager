/**
 * ============================================================
 * 详细信息类页面共用的画布骨架
 * ============================================================
 *
 * 两个页共用这一套皮：
 * - 网卡页长按菜单里的「查看网络详细信息(D)」；
 * - 性能页动作区那个「设备详细信息」入口。
 *
 * 它们内容不同、入口也不同，但"长什么样"必须是同一套 —— 顶部 48dp 标题栏
 * （返回箭头 + 页名）、一条分隔线、下面一整块可以自由摆放控件的工作区。
 * 两处各画一遍迟早走形，所以皮抽到这里，页面只决定标题和正文。
 *
 * 工作区现在是空的：只摆一张写着 [cardText] 的小卡片，默认居中，按住能拖到任意位置
 * （位移限制在半宽 / 半高以内，拖不出可视区）。以后要摆真控件，用 [content] 槽位往里加。
 */
package com.android.taskmanager

import com.android.taskmanager.AppStrings
import com.android.taskmanager.R

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 详细信息类页面的统一骨架。
 *
 * @param title 标题栏上的页名
 * @param backDescription 返回按钮的无障碍说明（"返回性能页" / AppStrings.s(R.string.common_back) 之类）
 * @param onBack 点返回箭头要做什么（关掉这一页、回到原来的页面）
 * @param cardText 工作区中央那张占位卡片上的字；传空串就不摆卡片
 * @param content 追加到工作区里的内容（以后真控件从这里进来），默认没有
 */
@Composable
internal fun DetailCanvasPage(
    title: String,
    onBack: () -> Unit,
    backDescription: String = AppStrings.s(R.string.common_back),
    cardText: String = AppStrings.s(R.string.common_test_page),
    content: @Composable BoxScope.() -> Unit = {}
) {
    // 控件相对"正中"的位移（dp）：初始都是 0，也就是居中；拖动只改这两个值
    var offsetX by remember { mutableStateOf(0f) }

    var offsetY by remember { mutableStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(start = 4.dp, end = 12.dp),

            verticalAlignment = Alignment.CenterVertically
        ) {

            IconButton(onClick = onBack) {

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,

                    contentDescription = backDescription
                )
            }

            Text(
                text = title,

                style = MaterialTheme.typography.titleMedium,

                fontWeight = FontWeight.SemiBold,

                maxLines = 1,

                overflow = TextOverflow.Ellipsis
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // 工作区：以后的新控件都摆这一层里
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {

            // 位移上限取"半宽 / 半高"：拖动时控件不会跑出可视区
            val limitX = maxWidth.value / 2f

            val limitY = maxHeight.value / 2f

            val density = LocalDensity.current

            if (cardText.isNotEmpty()) {

                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = offsetX.dp, y = offsetY.dp)
                        .pointerInput(Unit) {

                            detectDragGestures { change, dragAmount ->

                                change.consume()

                                val dx = dragAmount.x / density.density

                                val dy = dragAmount.y / density.density

                                offsetX = (offsetX + dx).coerceIn(-limitX, limitX)

                                offsetY = (offsetY + dy).coerceIn(-limitY, limitY)
                            }
                        }
                ) {

                    Surface(
                        shape = RoundedCornerShape(8.dp),

                        color = MaterialTheme.colorScheme.surfaceVariant,

                        tonalElevation = 2.dp
                    ) {

                        Text(
                            text = cardText,

                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),

                            style = MaterialTheme.typography.bodyLarge,

                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            content()
        }
    }
}
