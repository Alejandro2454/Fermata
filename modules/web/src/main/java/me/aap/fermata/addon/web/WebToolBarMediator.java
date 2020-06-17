package me.aap.fermata.addon.web;

import android.content.Context;
import android.view.KeyEvent;
import android.widget.EditText;

import androidx.constraintlayout.widget.ConstraintLayout;

import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.fragment.ActivityFragment;
import me.aap.utils.ui.view.ToolBarView;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_ENTER;
import static android.view.KeyEvent.KEYCODE_NUMPAD_ENTER;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.LEFT;
import static me.aap.utils.ui.UiUtils.toPx;

/**
 * @author Andrey Pavlenko
 */
public class WebToolBarMediator implements ToolBarView.Mediator {
	private static WebToolBarMediator instance = new WebToolBarMediator();

	public static WebToolBarMediator getInstance() {
		return instance;
	}

	@Override
	public void enable(ToolBarView tb, ActivityFragment f) {
		WebBrowserFragment b = (WebBrowserFragment) f;
		EditText t = createAddress(tb, b);
		String url = b.getUrl();
		if (url != null) t.setText(url);
		addView(tb, t, R.id.browser_addr, LEFT);
		ToolBarView.Mediator.super.enable(tb, f);
	}

	public void setAddress(ToolBarView tb, String addr) {
		EditText et = tb.findViewById(R.id.browser_addr);
		if (et != null) et.setText(addr);
	}

	private EditText createAddress(ToolBarView tb, WebBrowserFragment f) {
		Context ctx = tb.getContext();
		int p = (int) toPx(ctx, 2);
		EditText t = createEditText(tb);
		ConstraintLayout.LayoutParams lp = setLayoutParams(t, MATCH_PARENT, WRAP_CONTENT);
		t.setBackgroundResource(me.aap.utils.R.drawable.tool_bar_edittext_bg);
		t.setOnKeyListener((v, keyCode, event) -> onKey(f, t, keyCode, event));
		t.setMaxLines(1);
		t.setSingleLine(true);
		t.setPadding(p, p, p, p);
		lp.horizontalWeight = 2;
		return t;
	}

	private boolean onKey(WebBrowserFragment f, EditText text, int keyCode, KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

		switch (keyCode) {
			case KEYCODE_DPAD_CENTER:
			case KEYCODE_ENTER:
			case KEYCODE_NUMPAD_ENTER:
				f.loadUrl(text.getText().toString());
				return true;
			default:
				return UiUtils.dpadFocusHelper(text, keyCode, event);
		}
	}
}
