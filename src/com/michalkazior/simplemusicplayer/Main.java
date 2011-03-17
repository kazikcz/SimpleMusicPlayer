package com.michalkazior.simplemusicplayer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.michalkazior.simplemusicplayer.Player.State;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
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
	private TextView songTime;
	private SeekBar songSeekBar;
	private ListView enqueuedSongs;
	private Song[] songs = {};
	private Player.State state = State.IS_STOPPED;
	private Song selectedSong = null;
	private boolean isEmpty = false;

	/*
	 * Position of the seekbar before user started draging.
	 * 
	 * -1 means the user is not dragging the seekbar.
	 */
	private int oldSeekBarPosition = -1;

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
		songTime = (TextView) findViewById(R.id.songTime);
		songSeekBar = (SeekBar) findViewById(R.id.songSeekBar);
		enqueuedSongs = (ListView) findViewById(R.id.enqueuedSongs);

		playButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				switch (state) {
					case IS_PLAYING:
						sendBroadcast(Player.Remote.Request.Stop.getIntent());
						break;
					case IS_STOPPED:
					case IS_PAUSED:
						sendBroadcast(Player.Remote.Request.Play.getIntent());
						break;
				}
			}
		});

		skipButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				sendBroadcast(Player.Remote.Request.PlayNext.getIntent());
			}
		});

		/*
		 * We send a seek request when the user has lifted his finger.
		 */
		songSeekBar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
			private int lastProgress = 0;

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				oldSeekBarPosition = -1;
				sendBroadcast(Player.Remote.Request.Seek.getIntent().putExtra("position",
						lastProgress));
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
				oldSeekBarPosition = seekBar.getProgress();
			}

			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (fromUser) {
					lastProgress = progress;
				}
			}
		});

		registerForContextMenu(enqueuedSongs);
		enqueuedSongs.setOnItemClickListener(new OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				view.showContextMenu();
			}
		});

		isEmpty = false;
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setupEmptyView();

		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				/*
				 * Avoid updating when we display 'no songs enqueued'. It will
				 * get updated soon enough.
				 * 
				 * Also avoiding setupEmptyView() here fixes the case of end of
				 * playback which would not switch to 'no songs enqueued'
				 * screen.
				 */
				if (songs.length == 0) return;

				int duration = intent.getIntExtra("duration", 0);
				int position = intent.getIntExtra("position", 0);
				state = (State) intent.getSerializableExtra("state");

				switch (state) {
					case IS_STOPPED:
						songSeekBar.setMax(0);
						songSeekBar.setProgress(0);
						break;
					case IS_PLAYING:
					case IS_ON_HOLD_BY_CALL:
					case IS_ON_HOLD_BY_HEADSET:
					case IS_PAUSED:
						songTime.setText(String.format("%d:%02d / %d:%02d (%d%%)",
								(position / 1000) / 60, (position / 1000) % 60,
								(duration / 1000) / 60, (duration / 1000) % 60,
								Math.round(100 * position / duration)));
						if (oldSeekBarPosition == -1) {
							songSeekBar.setMax(duration);
							songSeekBar.setProgress(position);
						}
						break;
				}

				switch (state) {
					case IS_STOPPED:
						songTime.setText("");
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
		}, Player.Remote.Reply.State.getIntentFilter());

		/*
		 * Update enqueued songs listview upon Reply.EnqueuedSongs
		 */
		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				songs = Player.parcelableArrayToSongs(intent.getParcelableArrayExtra("songs"));
				switch (songs.length) {
					case 0:
						setupEmptyView();
						break;
					default:
						setupContentView();
						enqueuedSongs.setAdapter(new MainSongAdapter(
								getApplicationContext(),
								R.layout.listitem,
								songs));
						break;
				}
			}
		}, Player.Remote.Reply.EnqueuedSongs.getIntentFilter());

		/*
		 * If the service isn't running yet, the broadcast will be ignored.
		 */
		sendBroadcast(Player.Remote.Request.GetEnqueuedSongs.getIntent());
		sendBroadcast(Player.Remote.Request.GetState.getIntent());
		startService(new Intent(this, Player.class));
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
		AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
		selectedSong = (Song) enqueuedSongs.getItemAtPosition(info.position);

		menu.add(R.string.context_menu_play_now).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						if (songs[0] != selectedSong) {
							sendBroadcast(Player.Remote.Request.RemoveSong.getIntent().putExtra(
									"song", selectedSong));
							sendBroadcast(Player.Remote.Request.EnqueueSong
									.getIntent()
									.putExtra("song", selectedSong)
									.putExtra("index", 0));
						}
						return false;
					}
				});

		menu.add(R.string.context_menu_play_next).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						sendBroadcast(Player.Remote.Request.RemoveSong.getIntent().putExtra("song",
								selectedSong));
						sendBroadcast(Player.Remote.Request.EnqueueSong
								.getIntent()
								.putExtra("song", selectedSong)
								.putExtra("index", 1));
						return false;
					}
				});

		menu.add(R.string.context_menu_move_up).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						sendBroadcast(Player.Remote.Request.MoveSong
								.getIntent()
								.putExtra("song", selectedSong)
								.putExtra("offset", -1));
						return false;
					}
				});

		menu.add(R.string.context_menu_move_down).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						sendBroadcast(Player.Remote.Request.MoveSong
								.getIntent()
								.putExtra("song", selectedSong)
								.putExtra("offset", 1));
						return false;
					}
				});

		menu.add(R.string.context_menu_remove).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						sendBroadcast(Player.Remote.Request.RemoveSong.getIntent().putExtra("song",
								selectedSong));
						return false;
					}
				});

		menu.add(R.string.context_menu_clone).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						sendBroadcast(Player.Remote.Request.EnqueueSong.getIntent().putExtra(
								"song", selectedSong));
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
												sendBroadcast(Player.Remote.Request.Stop
														.getIntent());
												for (Song song : songs) {
													sendBroadcast(Player.Remote.Request.RemoveSong
															.getIntent()
															.putExtra("song", song));
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
												sendBroadcast(Player.Remote.Request.Stop
														.getIntent());
												List<Song> songsNew = Arrays.asList(songs);
												for (Song song : songsNew) {
													sendBroadcast(Player.Remote.Request.RemoveSong
															.getIntent()
															.putExtra("song", song));
												}
												Collections.shuffle(songsNew);
												for (Song song : songsNew) {
													sendBroadcast(Player.Remote.Request.EnqueueSong
															.getIntent()
															.putExtra("song", song));
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
						startActivity(new Intent(Main.this, Songlist.class));
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
		public MainSongAdapter(Context context, int textViewResourceId, Song[] objects) {
			super(context, textViewResourceId, objects);
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View v = super.getView(position, convertView, parent);
			if (v != null) {
				if (position == 0) v.setBackgroundDrawable(getResources().getDrawable(
						R.drawable.listitem_selector_first));
				else v.setBackgroundDrawable(getResources().getDrawable(
						R.drawable.listitem_selector));
			}
			return v;
		}
	};
}