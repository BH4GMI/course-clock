/*
 * Copyright 2019 Louis Cognault Ayeva Derman. Use of this source code is governed by the Apache 2.0 license.
 */

@file:Suppress("NOTHING_TO_INLINE")

package splitties.resources

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * 走 [ContextCompat] 而不是手写 `SDK_INT >= 23` 分支 + `resources.getColor(...)`：
 * 后者那条 deprecated 调用会让 appcompat 的 `BaseMethodDeprecationDetector`
 * 在 lint 分析中途崩溃（LintError，报在本文件），且 behavior 两者完全一致。
 *
 * @see [androidx.core.content.ContextCompat.getColor]
 */
@ColorInt
fun Context.color(@ColorRes colorRes: Int): Int = ContextCompat.getColor(this, colorRes)

inline fun Fragment.color(@ColorRes colorRes: Int) = context!!.color(colorRes)
inline fun View.color(@ColorRes colorRes: Int) = context.color(colorRes)

/**
 * @see [androidx.core.content.ContextCompat.getColorStateList]
 *
 * [ContextCompat.getColorStateList] 的返回值标注为可空（资源缺失时它返回 null 而不是
 * 抛异常）；本函数保持上游的非空契约 —— 资源 ID 来自编译期常量，缺资源属于编码错误，
 * 直接抛 IllegalStateException 而不是让调用方拿到 null。
 */
fun Context.colorSL(@ColorRes colorRes: Int): ColorStateList =
    checkNotNull(ContextCompat.getColorStateList(this, colorRes)) {
        "ColorStateList resource not found: #$colorRes"
    }

inline fun Fragment.colorSL(@ColorRes colorRes: Int) = context!!.colorSL(colorRes)
inline fun View.colorSL(@ColorRes colorRes: Int) = context.colorSL(colorRes)

// Styled resources below

private inline val defaultColor get() = Color.RED

@ColorInt
fun Context.styledColor(
        @AttrRes attr: Int
): Int = withStyledAttributes(attr) { getColor(it, defaultColor) }

inline fun Fragment.styledColor(@AttrRes attr: Int) = context!!.styledColor(attr)
inline fun View.styledColor(@AttrRes attr: Int) = context.styledColor(attr)

fun Context.styledColorSL(
        @AttrRes attr: Int
): ColorStateList? = withStyledAttributes(attr) { getColorStateList(it) }

inline fun Fragment.styledColorSL(@AttrRes attr: Int) = context!!.styledColorSL(attr)
inline fun View.styledColorSL(@AttrRes attr: Int) = context.styledColorSL(attr)
