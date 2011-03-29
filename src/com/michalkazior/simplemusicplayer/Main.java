package com.michalkazior.simplemusicplayer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import com.michalkazior.simplemusicplayer.Player.State;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.SeekBar.OnSeekBarChangeListener;

/**
 * Main activity.
 * 
 * The user may manage the list of enqueued songs, play, pause.
 */
public class Main extends Activity {
	private Button playButton, skipButton;
	private TextView songTimeTextView;
	private SeekBar songSeekBar;
	private ListView enqueuedSongsListView;

	private Song selectedSong = null;
	private boolean isEmpty = false;

	private boolean isDraggingSeekBar = false;
	private int seekZoomBegin = 0;
	private int seekZoomLength = 0;
	private Timer seekZoomTimer = new Timer();
	private Timer updateTimer = null;

	private Player player = null;
	private ServiceConnection playerConnection = new ServiceConnection() {
		@Override
		public void onServiceDisconnected(ComponentName name) {
			player = null;
		}

		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			player = ((Player.Proxy) service).getPlayer();
			player.registerHandler(playerMessenger);
			updateEnqueuedSongs();
			updatePlaying();
		}
	};

	private Messenger playerMessenger = new Messenger(new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (Player.Event.values()[msg.what]) {
				case EnqueuedSongsChanged:
					updateEnqueuedSongs();
					break;

				case StateChanged:
					enqueuedSongsListView.invalidateViews();
					updatePlaying();
					break;
			}
		}
	});

	private void updateEnqueuedSongs() {
		/*
		 * Avoid updating when we display 'no songs enqueued'. It will get
		 * updated soon enough.
		 * 
		 * Also avoiding setupEmptyView() here fixes the case of end of playback
		 * which would not switch to 'no songs enqueued' screen.
		 */
		if (player.getEnqueuedSongs().length == 0) {
			setupEmptyView();
			return;
		}

		setupContentView();
		((MainSongAdapter) enqueuedSongsListView.getAdapter()).setItems(player.getEnqueuedSongs());
	}

	private void updateTimerStart() {
		if (updateTimer != null) return;

		updateTimer = new Timer();
		updateTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				Main.this.runOnUiThread(new Runnable() {
					@Override
					public void run() {
						updatePlaying();
					}
				});
			}
		}, 1000, 1000);
	}

	private void updateTimerStop() {
		if (updateTimer == null) return;

		updateTimer.cancel();
		updateTimer.purge();
		updateTimer = null;
	}

	private void updatePlaying() {
		if (isEmpty) {
			updateTimerStop();
			return;
		}

		int duration = player.getDuration();
		int position = player.getPosition();
		State state = player.getState();

		switch (state) {
			case IS_PLAYING:
				updateTimerStart();
				break;
			case IS_STOPPED:
			case IS_ON_HOLD_BY_CALL:
			case IS_ON_HOLD_BY_HEADSET:
			case IS_PAUSED:
				updateTimerStop();
				break;
		}

		switch (state) {
			case IS_STOPPED:
				updatePosition(0, 0);
				break;
			case IS_PLAYING:
			case IS_ON_HOLD_BY_CALL:
			case IS_ON_HOLD_BY_HEADSET:
			case IS_PAUSED:
				if (!isDraggingSeekBar) updatePosition(position, duration);
				break;
		}

		switch (state) {
			case IS_STOPPED:
				songTimeTextView.setText("");
				break;
			case IS_PLAYING:
				playButton.setText(R.string.button_pause);
				break;
			case IS_ON_HOLD_BY_CALL:
			case IS_ON_HOLD_BY_HEADSET:
			case IS_PAUSED:
				playButton.setText(R.string.button_play);
				break;
		}
	}

	private void seekZoomTimerStart() {
		seekZoomTimerStop();

		seekZoomTimer.schedule(new TimerTask() {
			@Override
			public void run() {
				int position = Main.this.songSeekBar.getProgress();
				int duration = Main.this.songSeekBar.getMax();
				int start = position - (duration / 4);
				int length = duration / 2;

				if (start < 0) start = 0;
				if (start + length > duration) length = duration - start;

				seekZoomBegin += start;
				seekZoomLength = length;

				Main.this.runOnUiThread(new Runnable() {
					@Override
					public void run() {
						updatePosition(seekZoomLength / 2, seekZoomLength);
					}
				});

				seekZoomTimerStart();
			}
		}, 1000);
	}

	private void seekZoomTimerStop() {
		seekZoomTimer.cancel();
		seekZoomTimer.purge();
		seekZoomTimer = new Timer();
	}

	private void seekZoomReset() {
		seekZoomTimerStop();
		seekZoomBegin = 0;
		seekZoomLength = 0;
	}

	private synchronized void setupEmptyView() {
		if (isEmpty) return;

		setContentView(R.layout.main_empty);
		isEmpty = true;
	}

	private synchronized void setupContentView() {
		if (!isEmpty) return;

		setContentView(R.layout.main);

		playButton = (Button) findViewById(R.id.playButton);
		skipButton = (Button) findViewById(R.id.skipButton);
		songTimeTextView = (TextView) findViewById(R.id.songTime);
		songSeekBar = (SeekBar) findViewById(R.id.songSeekBar);
		enqueuedSongsListView = (ListView) findViewById(R.id.enqueuedSongs);

		enqueuedSongsListView.setAdapter(new MainSongAdapter(this, player.getEnqueuedSongs()));

		playButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				switch (Main.this.player.getState()) {
					case IS_PLAYING:
						Main.this.player.stop();
						break;
					case IS_ON_HOLD_BY_CALL:
					case IS_ON_HOLD_BY_HEADSET:
					case IS_STOPPED:
					case IS_PAUSED:
						Main.this.player.play();
						break;
				}
			}
		});

		skipButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				Main.this.player.playNext();
			}
		});

		/*
		 * We send a seek request when the user has lifted his finger.
		 */
		songSeekBar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
			private int lastProgress = 0;

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				isDraggingSeekBar = false;
				player.seek(Main.this.seekZoomBegin + lastProgress);
				seekZoomReset();
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
				isDraggingSeekBar = true;
				seekZoomTimerStart();
			}

			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (fromUser) {
					lastProgress = progress;
					updatePosition(progress, -1);
					seekZoomTimerStart();
				}
			}
		});

		registerForContextMenu(enqueuedSongsListView);
		enqueuedSongsListView.setOnItemClickListener(new OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				view.showContextMenu();
			}
		});

		isEmpty = false;
	}

	/**
	 * Update visually song position.
	 * 
	 * Updates the seekBar and songText.
	 * 
	 * @param position
	 * @param duration
	 */
	private void updatePosition(int position, int duration) {
		if (duration == -1) duration = songSeekBar.getMax();

		if (duration > 0) {
			String seekZoomText = "";
			int start = position + seekZoomBegin;
			int songDuration = player.getDuration();

			if (seekZoomLength != 0) {
				seekZoomText = String.format("[%d:%02d - %d:%02d]", (seekZoomBegin / 1000) / 60,
						(seekZoomBegin / 1000) % 60,
						((seekZoomBegin + seekZoomLength) / 1000) / 60,
						((seekZoomBegin + seekZoomLength) / 1000) % 60);
			}

			songTimeTextView.setText(String.format("%d:%02d / %d:%02d (%d%%)%s",
					(start / 1000) / 60, (start / 1000) % 60, (songDuration / 1000) / 60,
					(songDuration / 1000) % 60, Math.round(100 * start / songDuration),
					seekZoomText));
		}
		else {
			songTimeTextView.setText("");
		}
		songSeekBar.setMax(duration);
		songSeekBar.setProgress(position);
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setupEmptyView();

		startService(new Intent(Main.this, Player.class));
		bindService(new Intent(Main.this, Player.class), playerConnection, 0);
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
		AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
		selectedSong = (Song) enqueuedSongsListView.getItemAtPosition(info.position);

		menu.add(R.string.context_menu_play_now).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						player.reset();
						player.setPlaying(selectedSong);
						player.play();
						return false;
					}
				});

		menu.add(R.string.context_menu_play_next).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						player.removeSong(selectedSong);
						player.enqueueSong(selectedSong, Arrays
								.asList(player.getEnqueuedSongs())
								.indexOf(player.getPlaying()) + 1);
						return false;
					}
				});

		menu.add(R.string.context_menu_move_up).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						player.moveSong(selectedSong, -1);
						return false;
					}
				});

		menu.add(R.string.context_menu_move_down).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						player.moveSong(selectedSong, 1);
						return false;
					}
				});

		menu.add(R.string.context_menu_remove).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						player.removeSong(selectedSong);
						return false;
					}
				});

		menu.add(R.string.context_menu_clone).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						player.enqueueSong(selectedSong.spawn(), -1);
						return false;
					}
				});

		super.onCreateContextMenu(menu, v, menuInfo);
	}

	@Override
	public boolean onCreateOptionsMenu(android.view.Menu menu) {
		menu.add(R.string.option_menu_remove_all).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						new AlertDialog.Builder(Main.this)
								.setTitle(R.string.option_menu_remove_all)
								.setMessage(R.string.dialog_are_you_sure)
								.setNegativeButton(R.string.dialog_no, null)
								.setPositiveButton(R.string.dialog_yes,
										new Dialog.OnClickListener() {
											@Override
											public void onClick(DialogInterface dialog, int which) {
												player.stop();
												for (Song song : player.getEnqueuedSongs()) {
													player.removeSong(song);
												}
											}
										})
								.show();
						return false;
					}
				});

		menu.add(R.string.option_menu_shuffle).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						new AlertDialog.Builder(Main.this)
								.setTitle(R.string.option_menu_shuffle)
								.setMessage(R.string.dialog_are_you_sure)
								.setNegativeButton(R.string.dialog_no, null)
								.setPositiveButton(R.string.dialog_yes,
										new Dialog.OnClickListener() {
											@Override
											public void onClick(DialogInterface dialog, int which) {
												player.stop();

												List<Song> songsNew = Arrays.asList(player
														.getEnqueuedSongs());
												for (Song song : songsNew) {
													player.removeSong(song);
												}
												Collections.shuffle(songsNew);
												for (Song song : songsNew) {
													player.enqueueSong(song, -1);
												}
											}
										})
								.show();
						return false;
					}
				});

		menu.add(R.string.option_menu_enqueue_new).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						startActivity(new Intent(Main.this, SongList.class));
						return false;
					}
				});

		menu.add(R.string.option_menu_exit).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						new AlertDialog.Builder(Main.this)
								.setTitle(R.string.option_menu_exit)
								.setMessage(R.string.dialog_are_you_sure)
								.setNegativeButton(R.string.dialog_no, null)
								.setPositiveButton(R.string.dialog_yes,
										new Dialog.OnClickListener() {
											@Override
											public void onClick(DialogInterface dialog, int which) {
												unbindService(playerConnection);
												stopService(new Intent(Main.this, Player.class));
												finish();
											}
										})
								.show();
						return false;
					}
				});

		return super.onCreateOptionsMenu(menu);
	}

	private class MainSongAdapter extends SongAdapter {
		public MainSongAdapter(Context context, Song[] songs) {
			super(context, songs);
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View v = super.getView(position, convertView, parent);
			if (v != null) {
				if (Song.equals(player.getPlaying(), player.getEnqueuedSongs()[position])) {
					v.setBackgroundDrawable(getResources().getDrawable(
							R.drawable.listitem_selector_first));
				}
				else {
					v.setBackgroundDrawable(getResources()
							.getDrawable(R.drawable.listitem_selector));
				}
			}
			return v;
		}
	};
}