package ru.evrasia.research

import android.graphics.drawable.Drawable
import android.widget.Spinner

var Spinner.popupBackgroundDrawable: Drawable?
    get() = null
    set(value) {
        setPopupBackgroundDrawable(value)
    }
