package me.aap.fermata.ui.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.GestureDetectorCompat;

import com.google.android.material.textview.MaterialTextView;

import java.util.List;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.R;
import me.aap.fermata.media.engine.AudioStreamInfo;
import me.aap.fermata.media.engine.MediaEngine;
import me.aap.fermata.media.engine.SubtitleStreamInfo;
import me.aap.fermata.media.lib.MediaLib.BrowsableItem;
import me.aap.fermata.media.lib.MediaLib.Item;
import me.aap.fermata.media.lib.MediaLib.PlayableItem;
import me.aap.fermata.media.pref.BrowsableItemPrefs;
import me.aap.fermata.media.pref.MediaPrefs;
import me.aap.fermata.media.pref.PlaybackControlPrefs;
import me.aap.fermata.media.service.FermataServiceUiBinder;
import me.aap.fermata.media.service.MediaSessionCallback;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityListener;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.utils.app.App;
import me.aap.utils.function.BooleanSupplier;
import me.aap.utils.function.DoubleSupplier;
import me.aap.utils.function.IntSupplier;
import me.aap.utils.pref.BasicPreferenceStore;
import me.aap.utils.pref.PreferenceSet;
import me.aap.utils.pref.PreferenceStore;
import me.aap.utils.pref.PreferenceStore.Pref;
import me.aap.utils.text.SharedTextBuilder;
import me.aap.utils.text.TextUtils;
import me.aap.utils.ui.UiUtils;
import me.aap.utils.ui.menu.OverlayMenu;
import me.aap.utils.ui.menu.OverlayMenuItem;
import me.aap.utils.ui.view.GestureListener;
import me.aap.utils.ui.view.ImageButton;

import static android.media.AudioManager.ADJUST_LOWER;
import static android.media.AudioManager.ADJUST_RAISE;
import static android.media.AudioManager.FLAG_SHOW_UI;
import static android.media.AudioManager.STREAM_MUSIC;
import static androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;

/**
 * @author Andrey Pavlenko
 */
public class ControlPanelView extends LinearLayoutCompat implements MainActivityListener,
		PreferenceStore.Listener, OverlayMenu.SelectionHandler, GestureListener {
	private static final byte MASK_VISIBLE = 1;
	private static final byte MASK_VIDEO_MODE = 2;
	private final GestureDetectorCompat gestureDetector;
	private final ImageButton showHideBars;
	private final int timerTextAppearance;
	private PlaybackControlPrefs prefs;
	private HideTimer hideTimer;
	private byte mask;
	private View gestureSource;
	private TextView playbackTimer;
	private long scrollStamp;

	@SuppressLint("PrivateResource")
	public ControlPanelView(Context context, AttributeSet attrs) {
		super(context, attrs, R.attr.appControlPanelStyle);
		gestureDetector = new GestureDetectorCompat(context, this);
		setOrientation(VERTICAL);
		inflate(context, R.layout.control_panel_view, this);

		TypedArray ta = context.obtainStyledAttributes(attrs,
				new int[]{android.R.attr.colorBackground, R.attr.textAppearanceBody1},
				R.attr.appControlPanelStyle, R.style.AppTheme_ControlPanelStyle);
		setBackgroundColor(ta.getColor(0, Color.TRANSPARENT));
		timerTextAppearance = ta.getResourceId(1, R.style.TextAppearance_MaterialComponents_Body1);
		ta.recycle();

		MainActivityDelegate a = getActivity();
		a.addBroadcastListener(this, ACTIVITY_DESTROY);
		a.getPrefs().addBroadcastListener(this);

		ViewGroup g = findViewById(R.id.show_hide_bars);
		showHideBars = (ImageButton) g.getChildAt(0);
		g.setOnClickListener(this::showHideBars);
		showHideBars.setOnClickListener(this::showHideBars);
		g = findViewById(R.id.control_menu_button);
		g.setOnClickListener(this::showMenu);
		g.getChildAt(1).setOnClickListener(this::showMenu);
		setShowHideBarsIcon(a);
	}

	@Nullable
	@Override
	protected Parcelable onSaveInstanceState() {
		Parcelable parentState = super.onSaveInstanceState();
		Bundle b = new Bundle();
		b.putByte("MASK", mask);
		b.putParcelable("PARENT", parentState);
		return b;
	}

	@Override
	protected void onRestoreInstanceState(Parcelable st) {
		if (st instanceof Bundle) {
			Bundle b = (Bundle) st;
			super.onRestoreInstanceState(b.getParcelable("PARENT"));
			mask = b.getByte("MASK");
			if (mask != MASK_VISIBLE) super.setVisibility(GONE);
		}
	}

	public void bind(FermataServiceUiBinder b) {
		prefs = b.getMediaSessionCallback().getPlaybackControlPrefs();
		b.bindControlPanel(this);
		b.bindPrevButton(findViewById(R.id.control_prev));
		b.bindRwButton(findViewById(R.id.control_rw));
		b.bindPlayPauseButton(findViewById(R.id.control_play_pause));
		b.bindFfButton(findViewById(R.id.control_ff));
		b.bindNextButton(findViewById(R.id.control_next));
		b.bindProgressBar(findViewById(R.id.seek_bar));
		b.bindProgressTime(findViewById(R.id.seek_time));
		b.bindProgressTotal(findViewById(R.id.seek_total));
		b.bound();
	}

	public boolean isActive() {
		return mask != 0;
	}

	@Override
	public void setVisibility(int visibility) {
		MainActivityDelegate a = getActivity();

		if (visibility == VISIBLE) {
			mask |= MASK_VISIBLE;
			if ((mask & MASK_VIDEO_MODE) != 0) return;

			super.setVisibility(VISIBLE);

			if (a.getPrefs().getHideBarsPref()) {
				a.setBarsHidden(true);
				setShowHideBarsIcon(a);
			}
		} else {
			mask &= ~MASK_VISIBLE;
			super.setVisibility(GONE);
			a.getFloatingButton().setVisibility(VISIBLE);

			if (a.isBarsHidden()) {
				a.setBarsHidden(false);
				setShowHideBarsIcon(a);
			}
		}

		checkPlaybackTimer(a.getMediaSessionCallback());
	}

	public void enableVideoMode(@Nullable VideoView v) {
		MainActivityDelegate a = getActivity();
		hideTimer = null;
		mask |= MASK_VIDEO_MODE;

		a.setBarsHidden(true);
		setShowHideBarsIcon(a);

		View title = (v != null) ? v.getTitle() : null;
		View fb = a.getFloatingButton();
		int delay = getStartDelay();

		if (delay == 0) {
			fb.setVisibility(GONE);
			if (title != null) title.setVisibility(GONE);
			super.setVisibility(GONE);
		} else {
			fb.setVisibility(VISIBLE);
			if (title != null) title.setVisibility(VISIBLE);
			super.setVisibility(VISIBLE);
			hideTimer = new HideTimer(title, fb);
			App.get().getHandler().postDelayed(hideTimer, delay);
		}

		checkPlaybackTimer(a.getMediaSessionCallback());
	}

	public void disableVideoMode() {
		MainActivityDelegate a = getActivity();
		hideTimer = null;
		mask &= ~MASK_VIDEO_MODE;
		a.getFloatingButton().setVisibility(VISIBLE);

		if ((mask & MASK_VISIBLE) == 0) {
			super.setVisibility(GONE);
			a.setBarsHidden(false);
		} else {
			super.setVisibility(VISIBLE);
			a.setBarsHidden(a.getPrefs().getHideBarsPref());
		}

		setShowHideBarsIcon(a);
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent e) {
		if (hideTimer != null) {
			hideTimer = new HideTimer(hideTimer.views);
			FermataApplication.get().getHandler().postDelayed(hideTimer, getTouchDelay());
		}
		return getActivity().interceptTouchEvent(e, me -> {
			gestureSource = this;
			gestureDetector.onTouchEvent(me);
			return super.onTouchEvent(me);
		});
	}

	@Override
	public boolean onSwipeLeft(MotionEvent e1, MotionEvent e2) {
		getActivity().getMediaServiceBinder().onPrevNextButtonClick(true);
		return true;
	}

	@Override
	public boolean onSwipeRight(MotionEvent e1, MotionEvent e2) {
		getActivity().getMediaServiceBinder().onPrevNextButtonClick(false);
		return true;
	}

	@Override
	public boolean onSwipeUp(MotionEvent e1, MotionEvent e2) {
		getActivity().getMediaServiceBinder().onPrevNextFolderClick(false);
		return true;
	}

	@Override
	public boolean onSwipeDown(MotionEvent e1, MotionEvent e2) {
		getActivity().getMediaServiceBinder().onPrevNextFolderClick(true);
		return true;
	}

	@Override
	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
		boolean horizontal = Math.abs(distanceX) >= Math.abs(distanceY);
		long time = System.currentTimeMillis();
		long diff;

		if (horizontal) {
			diff = time - scrollStamp;
			if (diff < 100) return true;
			scrollStamp = time;
		} else {
			diff = time + scrollStamp;
			if (diff < 100) return true;
			scrollStamp = -time;
		}

		if (diff > 500) return true;

		if (horizontal) {
			FermataServiceUiBinder b = getActivity().getMediaServiceBinder();

			switch (e2.getPointerCount()) {
				case 1:
					b.onRwFfButtonClick(distanceX < 0);
					break;
				case 2:
					b.onRwFfButtonLongClick(distanceX < 0);
					break;
				default:
					b.onPrevNextButtonLongClick(distanceX < 0);
					break;
			}

			onVideoSeek();
		} else {
			AudioManager amgr = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
			if (amgr == null) return false;
			amgr.adjustStreamVolume(STREAM_MUSIC, (distanceY > 0) ? ADJUST_RAISE : ADJUST_LOWER, FLAG_SHOW_UI);
		}

		return true;
	}

	@Override
	public boolean onDoubleTap(MotionEvent e) {
		if (!(gestureSource instanceof VideoView)) return false;
		getActivity().getMediaServiceBinder().onPlayPauseButtonClick();
		return true;
	}

	@Override
	public boolean onSingleTapConfirmed(MotionEvent e) {
		if (!(gestureSource instanceof VideoView)) return false;

		int delay = getTouchDelay();
		if (delay == 0) return false;

		MainActivityDelegate a = getActivity();
		View title = ((VideoView) gestureSource).getTitle();
		View fb = a.getFloatingButton();

		if (getVisibility() == VISIBLE) {
			super.setVisibility(GONE);
			title.setVisibility(GONE);
			fb.setVisibility(GONE);
		} else {
			super.setVisibility(VISIBLE);
			title.setVisibility(VISIBLE);
			fb.setVisibility(VISIBLE);
			clearFocus();
			hideTimer = new HideTimer(title, fb);
			App.get().getHandler().postDelayed(hideTimer, delay);
		}

		checkPlaybackTimer(a.getMediaSessionCallback());
		return true;
	}

	public void onVideoViewTouch(VideoView view, MotionEvent e) {
		gestureSource = view;
		gestureDetector.onTouchEvent(e);
	}

	public void onVideoSeek() {
		MainActivityDelegate a = getActivity();
		VideoView vv = a.getMediaServiceBinder().getMediaSessionCallback().getVideoView();

		if (vv == null) {
			if (gestureSource instanceof VideoView) vv = (VideoView) gestureSource;
			else return;
		}

		View title = vv.getTitle();
		View fb = a.getFloatingButton();
		super.setVisibility(VISIBLE);
		title.setVisibility(VISIBLE);
		fb.setVisibility(VISIBLE);
		clearFocus();
		hideTimer = new HideTimer(title, fb);
		App.get().getHandler().postDelayed(hideTimer, getSeekDelay());
		checkPlaybackTimer(a.getMediaSessionCallback());
	}

	@Override
	public void onActivityEvent(MainActivityDelegate a, long e) {
		if (handleActivityDestroyEvent(a, e)) {
			a.getMediaServiceBinder().unbind();
			a.getPrefs().removeBroadcastListener(this);
		}
	}

	@Override
	public void onPreferenceChanged(PreferenceStore store, List<Pref<?>> prefs) {
		if (mask != MASK_VISIBLE) return;

		if (prefs.contains(MainActivityPrefs.HIDE_BARS)) {
			MainActivityDelegate a = getActivity();
			if (a.getPrefs().getHideBarsPref()) a.setBarsHidden(getVisibility() == VISIBLE);
			else if (a.isBarsHidden()) a.setBarsHidden(false);
			setShowHideBarsIcon(a);
		}
	}

	private void showHideBars(View v) {
		MainActivityDelegate a = getActivity();
		a.setBarsHidden(!a.isBarsHidden());
		setShowHideBarsIcon(a);
	}

	public void showMenu() {
		if (isActive()) showMenu(this);
	}

	private void showMenu(View v) {
		MainActivityDelegate a = getActivity();
		FermataServiceUiBinder b = a.getMediaServiceBinder();
		PlayableItem i = b.getCurrentItem();
		if (i == null) return;

		MenuHandler h = new MenuHandler(getMenu(a), i);
		h.show();
	}

	private OverlayMenu getMenu(MainActivityDelegate a) {
		return a.findViewById(R.id.control_menu);
	}

	private void setShowHideBarsIcon(MainActivityDelegate a) {
		showHideBars.setImageResource(a.isBarsHidden() ? R.drawable.expand : R.drawable.collapse);
	}

	private MainActivityDelegate getActivity() {
		return MainActivityDelegate.get(getContext());
	}

	@Override
	public boolean menuItemSelected(OverlayMenuItem item) {
		return true;
	}

	private void checkPlaybackTimer(MediaSessionCallback cb) {
		int t = cb.getPlaybackTimer();

		if (t <= 0) {
			if (playbackTimer != null) {
				((ViewGroup) getParent()).removeView(playbackTimer);
				playbackTimer = null;
			}
		} else {
			if (playbackTimer == null) {
				Context ctx = getContext();
				playbackTimer = new MaterialTextView(ctx);
				((ViewGroup) getParent()).addView(playbackTimer);
				playbackTimer.setBackgroundResource(R.drawable.playback_timer_bg);
				playbackTimer.setTextAppearance(timerTextAppearance);
				ViewGroup.LayoutParams lp = playbackTimer.getLayoutParams();

				if (lp instanceof ConstraintLayout.LayoutParams) {
					ConstraintLayout.LayoutParams clp = (ConstraintLayout.LayoutParams) lp;
					clp.startToStart = PARENT_ID;
					clp.endToEnd = PARENT_ID;
					clp.bottomToTop = getId();
					clp.resolveLayoutDirection(LAYOUT_DIRECTION_LTR);
				}

				playbackTimer.setOnClickListener(v -> {
					MainActivityDelegate a = getActivity();
					getMenu(a).show(b -> new TimerMenuHandler(a).build(b));
				});
			}

			if (getVisibility() != VISIBLE) {
				playbackTimer.setVisibility(GONE);
				return;
			}

			try (SharedTextBuilder tb = SharedTextBuilder.get()) {
				TextUtils.timeToString(tb, t);
				playbackTimer.setText(tb);
			}

			playbackTimer.setVisibility(VISIBLE);
			App.get().getHandler().postDelayed(() -> checkPlaybackTimer(cb), 1000);
		}
	}

	private final class MenuHandler extends MediaItemMenuHandler {

		public MenuHandler(OverlayMenu menu, Item item) {
			super(menu, item);
		}

		@Override
		protected void buildPlayableMenu(MainActivityDelegate a, OverlayMenu.Builder b, PlayableItem pi,
																		 boolean initRepeat) {
			super.buildPlayableMenu(a, b, pi, false);

			BrowsableItemPrefs p = pi.getParent().getPrefs();
			MediaEngine eng = a.getMediaSessionCallback().getEngine();
			if (eng == null) return;

			eng.contributeToMenu(b);

			if (!pi.isExternal()) {
				if (pi.isRepeatItemEnabled() || p.getRepeatPref()) {
					b.addItem(R.id.repeat, R.drawable.repeat_filled, R.string.repeat)
							.setSubmenu(s -> {
								buildRepeatMenu(s);
								s.addItem(R.id.repeat_disable_all, R.string.repeat_disable);
							});
				} else {
					b.addItem(R.id.repeat_enable, R.drawable.repeat, R.string.repeat).setSubmenu(this::buildRepeatMenu);
				}

				if (p.getShufflePref()) {
					b.addItem(R.id.shuffle_disable, R.drawable.shuffle_filled, R.string.shuffle_disable);
				} else {
					b.addItem(R.id.shuffle_enable, R.drawable.shuffle, R.string.shuffle);
				}
			}

			if (eng.getAudioEffects() != null) {
				b.addItem(R.id.audio_effects_fragment, R.drawable.equalizer, R.string.audio_effects);
			}

			b.addItem(R.id.speed, R.drawable.speed, R.string.speed).setSubmenu(s -> new SpeedMenuHandler().build(s, getItem()));
			b.addItem(R.id.timer, R.drawable.timer, R.string.timer).setSubmenu(s -> new TimerMenuHandler(a).build(s));
		}

		@Override
		protected void buildVideoMenu(OverlayMenu.Builder b) {
			super.buildVideoMenu(b);

			MediaEngine eng = getActivity().getMediaSessionCallback().getEngine();
			if (eng == null) return;
			PlayableItem pi = (PlayableItem) getItem();

			if (pi.isVideo()) {
				if (eng.getAudioStreamInfo().size() > 1) {
					b.addItem(R.id.select_audio_stream, R.string.select_audio_stream)
							.setSubmenu(this::buildAudioStreamMenu);
				}
				if (!eng.getSubtitleStreamInfo().isEmpty()) {
					b.addItem(R.id.select_subtitles, R.string.select_subtitles)
							.setSubmenu(this::buildSubtitleStreamMenu);
				}
			}
		}

		private void buildRepeatMenu(OverlayMenu.Builder b) {
			b.setSelectionHandler(this);
			b.addItem(R.id.repeat_track, R.string.current_track);
			b.addItem(R.id.repeat_folder, R.string.current_folder);
		}

		private void buildAudioStreamMenu(OverlayMenu.Builder b) {
			MediaEngine eng = getActivity().getMediaSessionCallback().getEngine();
			if (eng == null) return;
			AudioStreamInfo ai = eng.getCurrentAudioStreamInfo();
			List<AudioStreamInfo> streams = eng.getAudioStreamInfo();
			b.setSelectionHandler(this::audioStreamSelected);

			for (int i = 0; i < streams.size(); i++) {
				AudioStreamInfo s = streams.get(i);
				b.addItem(UiUtils.getArrayItemId(i), s.toString()).setData(s).setChecked(s.equals(ai));
			}
		}

		private boolean audioStreamSelected(OverlayMenuItem i) {
			MediaEngine eng = getActivity().getMediaSessionCallback().getEngine();
			if (eng != null) {
				AudioStreamInfo ai = i.getData();
				PlayableItem pi = (PlayableItem) getItem();

				if (ai.equals(eng.getCurrentAudioStreamInfo())) {
					pi.getPrefs().setAudioIdPref(null);
					eng.setCurrentAudioStream(null);
				} else {
					eng.setCurrentAudioStream(ai);
					pi.getPrefs().setSubIdPref(ai.getId());
				}
			}
			return true;
		}

		private void buildSubtitleStreamMenu(OverlayMenu.Builder b) {
			MediaEngine eng = getActivity().getMediaSessionCallback().getEngine();
			if (eng == null) return;
			SubtitleStreamInfo si = eng.getCurrentSubtitleStreamInfo();
			List<SubtitleStreamInfo> streams = eng.getSubtitleStreamInfo();
			b.setSelectionHandler(this::subtitleStreamSelected);

			for (int i = 0; i < streams.size(); i++) {
				SubtitleStreamInfo s = streams.get(i);
				b.addItem(UiUtils.getArrayItemId(i), s.toString()).setData(s).setChecked(s.equals(si));
			}
		}

		private boolean subtitleStreamSelected(OverlayMenuItem i) {
			MediaEngine eng = getActivity().getMediaSessionCallback().getEngine();
			if (eng != null) {
				SubtitleStreamInfo si = i.getData();
				PlayableItem pi = (PlayableItem) getItem();

				if (si.equals(eng.getCurrentSubtitleStreamInfo())) {
					pi.getPrefs().setSubIdPref(null);
					eng.setCurrentSubtitleStream(null);
				} else {
					eng.setCurrentSubtitleStream(si);
					pi.getPrefs().setSubIdPref(si.getId());
				}
			}
			return true;
		}

		@Override
		public boolean menuItemSelected(OverlayMenuItem i) {
			int id = i.getItemId();
			PlayableItem pi;
			MediaEngine eng;

			switch (id) {
				case R.id.audio_effects_fragment:
					eng = getActivity().getMediaSessionCallback().getEngine();
					if ((eng != null) && (eng.getAudioEffects() != null))
						getActivity().showFragment(R.id.audio_effects_fragment);
					return true;
				case R.id.repeat_track:
				case R.id.repeat_folder:
				case R.id.repeat_disable_all:
					pi = (PlayableItem) getItem();
					pi.setRepeatItemEnabled(id == R.id.repeat_track);
					pi.getParent().getPrefs().setRepeatPref(id == R.id.repeat_folder);
					return true;
				case R.id.shuffle_enable:
				case R.id.shuffle_disable:
					pi = (PlayableItem) getItem();
					pi.getParent().getPrefs().setShufflePref(id == R.id.shuffle_enable);
					return true;
				default:
					return super.menuItemSelected(i);
			}
		}
	}

	private final class SpeedMenuHandler implements OverlayMenu.CloseHandler {
		private PrefStore store;

		void build(OverlayMenu.Builder b, Item item) {
			store = new PrefStore(item);
			PreferenceSet set = new PreferenceSet();

			set.addFloatPref(o -> {
				o.title = R.string.speed;
				o.store = store;
				o.pref = MediaPrefs.SPEED;
				o.scale = 0.1f;
				o.seekMin = 1;
				o.seekMax = 20;
			});
			set.addBooleanPref(o -> {
				o.title = R.string.current_track;
				o.store = store;
				o.pref = store.TRACK;
			});
			set.addBooleanPref(o -> {
				o.title = R.string.current_folder;
				o.store = store;
				o.pref = store.FOLDER;
			});

			set.addToMenu(b, true);
			b.setCloseHandlerHandler(this);
		}

		@Override
		public void menuClosed(OverlayMenu menu) {
			store.apply();
		}

		private class PrefStore extends BasicPreferenceStore {
			final Pref<BooleanSupplier> TRACK = Pref.b("TRACK", false);
			final Pref<BooleanSupplier> FOLDER = Pref.b("FOLDER", false);
			private final MediaSessionCallback cb = getActivity().getMediaServiceBinder().getMediaSessionCallback();
			private final Item item;

			PrefStore(Item item) {
				this.item = item;
				MediaPrefs prefs = item.getPrefs();
				BrowsableItem p = item.getParent();
				boolean set = false;

				try (PreferenceStore.Edit edit = editPreferenceStore()) {
					if (prefs.hasPref(MediaPrefs.SPEED)) {
						edit.setBooleanPref(TRACK, true);
						edit.setFloatPref(MediaPrefs.SPEED, prefs.getFloatPref(MediaPrefs.SPEED));
						set = true;
					} else {
						edit.setBooleanPref(TRACK, false);
					}

					if (p != null) {
						prefs = p.getPrefs();

						if (prefs.hasPref(MediaPrefs.SPEED)) {
							edit.setBooleanPref(FOLDER, true);

							if (!set) {
								edit.setFloatPref(MediaPrefs.SPEED, prefs.getFloatPref(MediaPrefs.SPEED));
								set = true;
							}
						} else {
							edit.setBooleanPref(FOLDER, false);
						}
					} else {
						edit.setBooleanPref(FOLDER, false);
					}

					if (!set)
						edit.setFloatPref(MediaPrefs.SPEED, cb.getPlaybackControlPrefs().getFloatPref(MediaPrefs.SPEED));
				}
			}

			void apply() {
				BrowsableItem p = item.getParent();
				boolean set = false;

				if (getBooleanPref(TRACK)) {
					item.getPrefs().applyFloatPref(MediaPrefs.SPEED, getFloatPref(MediaPrefs.SPEED));
					set = true;
				} else {
					item.getPrefs().removePref(MediaPrefs.SPEED);
				}

				if (p != null) {
					if (getBooleanPref(FOLDER)) {
						p.getPrefs().applyFloatPref(MediaPrefs.SPEED, getFloatPref(MediaPrefs.SPEED));
						set = true;
					} else {
						p.getPrefs().removePref(MediaPrefs.SPEED);
					}
				}

				if (!set) {
					cb.getPlaybackControlPrefs().applyFloatPref(MediaPrefs.SPEED, getFloatPref(MediaPrefs.SPEED));
				}
			}

			@Override
			public void applyFloatPref(boolean removeDefault, Pref<? extends DoubleSupplier> pref, float value) {
				if (value == 0.0f) value = 0.1f;
				super.applyFloatPref(removeDefault, pref, value);
				if (cb.isPlaying()) cb.onSetPlaybackSpeed(value);
			}
		}
	}

	private final class TimerMenuHandler extends BasicPreferenceStore implements OverlayMenu.CloseHandler {
		private final Pref<IntSupplier> H = Pref.i("H", 0);
		private final Pref<IntSupplier> M = Pref.i("M", 0);
		private final MediaSessionCallback cb;
		private boolean changed;
		private boolean closed;

		TimerMenuHandler(MainActivityDelegate activity) {
			cb = activity.getMediaSessionCallback();
		}

		void build(OverlayMenu.Builder b) {
			PreferenceSet set = new PreferenceSet();
			int time = cb.getPlaybackTimer();

			if (time > 0) {
				int h = time / 3600;
				int m = (time - h * 3600) / 60;
				applyIntPref(H, h);
				applyIntPref(M, m);
			}

			set.addIntPref(o -> {
				o.title = R.string.hours;
				o.store = this;
				o.pref = H;
				o.seekMin = 0;
				o.seekMax = 12;
			});
			set.addIntPref(o -> {
				o.title = R.string.minutes;
				o.store = this;
				o.pref = M;
				o.seekMin = 0;
				o.seekMax = 60;
				o.seekScale = 5;
			});

			set.addToMenu(b, true);
			b.setCloseHandlerHandler(this);
			changed = false;
			startTimer();
		}

		@Override
		public void applyIntPref(boolean removeDefault, Pref<? extends IntSupplier> pref, int value) {
			super.applyIntPref(removeDefault, pref, value);
			changed = true;
			startTimer();
		}

		@Override
		public void menuClosed(OverlayMenu menu) {
			closed = true;
			if (!changed) return;
			int h = getIntPref(H);
			int m = getIntPref(M);
			cb.setPlaybackTimer(h * 3600 + m * 60);
			checkPlaybackTimer(cb);
		}

		private void startTimer() {
			App.get().getHandler().postDelayed(() -> {
				if (!closed) {
					MainActivityDelegate a = getActivity();
					if (a != null) getMenu(a).hide();
				}
			}, 60000);
		}
	}

	private int getStartDelay() {
		return (prefs == null) ? 0 : prefs.getVideoControlStartDelayPref() * 1000;
	}

	private int getTouchDelay() {
		return (prefs == null) ? 5000 : prefs.getVideoControlTouchDelayPref() * 1000;
	}

	private int getSeekDelay() {
		return (prefs == null) ? 3000 : prefs.getVideoControlSeekDelayPref() * 1000;
	}

	private final class HideTimer implements Runnable {
		final View[] views;

		HideTimer(View... views) {
			this.views = views;
		}

		@Override
		public void run() {
			if ((hideTimer == this) && ((mask & MASK_VIDEO_MODE) != 0)) {
				ControlPanelView.super.setVisibility(GONE);

				for (View v : views) {
					v.setVisibility(GONE);
				}
			}
		}
	}
}
