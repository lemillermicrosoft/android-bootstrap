package com.example.test;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

public class EditTextBackEvent extends EditText {

    Boolean modifyingLabel = false;

    public EditTextBackEvent(Context context) {
        super(context);
    }

    public EditTextBackEvent(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public EditTextBackEvent(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
//        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
//            // Do your thing.
//            this.setFocusable(0);
//            return true;  // So it is not propagated.
//        }
//        if (event.getKeyCode() == KeyEvent.ACTION_DOWN) {
//            System.out.println("downnnn");
//            this.setFocusable(0);
//        }
        modifyingLabel = false;
        return super.dispatchKeyEvent(event);
    }

}