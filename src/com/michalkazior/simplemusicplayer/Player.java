package com.michalkazior.simplemusicplayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import com.michalkazior.simplemusicplayer.Player.Remote.Reply;
import com.michalkazior.simplemusicplayer.Player.Remote.Request;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.net.Uri;
import android.os.IBinder;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.telephony.TelephonyManager;
import android.widget.Toast;

/**
 * Player backend.
 * 
 * It manages the playlist, handles operations, sends events to UI client(s).
 * 
 * The communication between Player and other Activities is handles via
 * broadcasting and catching Intents.
 * 
 * The Intents are constructed as follows:
 * 
 * <pre>
 *    com.michalkazior.simplemusicplayer.Player.Remote.Request.GetState
 *    ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *          package namespace            ^^^^^^^^^^^^^^
 *                                           class     ^^^^^^^^
 *                                           prefix      intent ^^^^^^^
 *                                                        type   method
 * </pre>
 * 
 * @author kazik
 */
public class Player extends Service {
	/**
	 * Remote interface to Player class.
	 */
	public static class Remote {
		/**
		 * Request are handled asynchronously.
		 */
		public enum Request {
			GetAvailableSongs, GetEnqueuedSongs, GetState, EnqueueSong, MoveSong, RemoveSong, Play,
			PlayNext, Stop, Seek;

			public Intent getIntent() {
				return new Intent(toString());
			}

			public IntentFilter getIntentFilter() {
				return new IntentFilter(toString());
			}

			@Override
			public String toString() {
				return this.getClass().toString() + "." + super.toString();
			}
		};

		/**
		 * Replies may be spurious, i.e. without requesting them first.
		 * 
		 * This is the case especially with 'state'. Other may be induced
		 * indirectly, e.g. 'enqueuedSongs' when removing a song from enqueued
		 * songs list.
		 */
		public enum Reply {
			AvailableSongs, EnqueuedSongs, State;

			public Intent getIntent() {
				return new Intent(toString());
			}

			public IntentFilter getIntentFilter() {
				return new IntentFilter(toString());
			}

			@Override
			public String toString() {
				return this.getClass().toString() + "." + super.toString();
			}
		};
	};

	/**
	 * Possible Player states.
	 */
	public enum State {
		IS_STOPPED, IS_PLAYING, IS_PAUSED, IS_ON_HOLD_BY_HEADSET, IS_ON_HOLD_BY_CALL,
	};

	private ArrayList<Song> enqueuedSongs = new ArrayList<Song>();
	private MediaPlayer mp = new MediaPlayer();
	private State state = State.IS_STOPPED;
	private Song nowPlaying = null;
	/**
	 * Timer is used to notify the UI about position updates.
	 */
	private Timer timer = null;

	/**
	 * Make sure the timer is running.
	 * 
	 * May be called multiple number of times.
	 */
	private void startTimer() {
		if (timer == null) {
			timer = new Timer();
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					sendState();
				}
			}, 0, 1000);
		}
	}

	/**
	 * Make sure the timer is stopped.
	 * 
	 * May be called multiple number of times.
	 */
	private void stopTimer() {
		if (timer != null) {
			timer.cancel();
			timer = null;
			sendState();
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	/**
	 * Broadcast Reply.availableSongs
	 * 
	 * The Intent contains:
	 * 
	 * <pre>
	 *  - songs (Song [])
	 * </pre>
	 */
	public void sendAvailableSongs() {
		sendBroadcast(Reply.AvailableSongs.getIntent().putExtra("songs", getSongsAvailable()));
	}

	/**
	 * Broadcast Reply.enqueuedSongs
	 * 
	 * The Intent contains:
	 * 
	 * <pre>
	 *  - songs (Song [])
	 * </pre>
	 */
	public synchronized void sendEnqueuedSongs() {
		sendBroadcast(Reply.EnqueuedSongs.getIntent().putExtra("songs",
				enqueuedSongs.toArray(new Song[] {})));
	}

	/**
	 * Broadcast Reply.state
	 * 
	 * The Intent contains:
	 * 
	 * <pre>
	 *  - duration in msec (int)
	 *  - position in msec (int)
	 *  - state (Player.State)
	 * </pre>
	 */
	public synchronized void sendState() {
		/*
		 * Fix spurious MediaPlayer.OnCompletion
		 * 
		 * It seems calling getDuration/getCurrentPosition in between the
		 * reset() and prepare()/start() combo is introducing spurious and
		 * unwanted stream completion event.
		 * 
		 * It is assumed that when the MediaPlayer instance is stopped/reseted
		 * it sets its duration and/or position to the same value (possibly 0?)
		 * and that triggers the completion event.
		 */
		switch (state) {
			case IS_ON_HOLD_BY_CALL:
			case IS_ON_HOLD_BY_HEADSET:
			case IS_PLAYING:
			case IS_PAUSED:
				sendBroadcast(Reply.State
						.getIntent()
						.putExtra("duration", mp.getDuration())
						.putExtra("position", mp.getCurrentPosition())
						.putExtra("state", state));
				break;
			case IS_STOPPED:
				sendBroadcast(Reply.State
						.getIntent()
						.putExtra("duration", 0)
						.putExtra("position", 0)
						.putExtra("state", state));
				break;
		}
	}

	/**
	 * Enqueue a song at a given index.
	 * 
	 * The currently playing song will be stopped and shifted down if a song is
	 * enqueued at index 0.
	 * 
	 * @param song
	 * @param index
	 *            value less than 0 appends
	 */
	public synchronized void enqueueSong(Song song, int index) {
		/*
		 * Spawn a copy, so the instance ID is different.
		 */
		song = song.spawn();

		if (index >= 0) {
			if (index > enqueuedSongs.size()) index = 0;
			enqueuedSongs.add(index, song);
		}
		else {
			enqueuedSongs.add(song);
		}

		sendEnqueuedSongs();

		/*
		 * This makes the Player start playing when a first song is enqueued.
		 */
		validate();
	}

	/**
	 * Move a song by an offset.
	 * 
	 * Moving a nowPlaying (i.e. the first) song restarts playback with a new
	 * song at index 0.
	 * 
	 * Moving an only song yields no effect.
	 * 
	 * Moving a song beyond enqueued songs list is done by cuting offset to list
	 * size accordingly.
	 * 
	 * @param song
	 * @param offset
	 */
	public synchronized void moveSong(Song song, int offset) {
		int index = enqueuedSongs.indexOf(song) + offset;

		if (index < 0) index = 0;
		if (index >= enqueuedSongs.size()) index = enqueuedSongs.size() - 1;

		enqueuedSongs.remove(song);
		enqueuedSongs.add(index, song);

		sendEnqueuedSongs();
		validate();
	}

	/**
	 * Remove a song.
	 * 
	 * Removing a now playing song (i.e. the first) restarts playback with a new
	 * song at index 0.
	 * 
	 * Removing a non-existing song may send Reply.enqueuedSongs
	 * 
	 * @param song
	 */
	public synchronized void removeSong(Song song) {
		enqueuedSongs.remove(song);

		sendEnqueuedSongs();
		validate();
	}

	/**
	 * Make sure the playback is on.
	 * 
	 * This call is valid in any state.
	 */
	public synchronized void play() {
		switch (state) {
			case IS_STOPPED:
				if (enqueuedSongs.size() > 0) {
					try {
						nowPlaying = enqueuedSongs.get(0);
						mp.setDataSource(nowPlaying.getPath());
						mp.prepare();
						mp.start();
						state = State.IS_PLAYING;
						startTimer();
					}
					catch (IOException e) {
						Toast.makeText(this, R.string.msg_err_io, Toast.LENGTH_LONG).show();
					}
				}
				break;

			case IS_PLAYING:
				/* ignore */
				break;

			case IS_ON_HOLD_BY_CALL:
			case IS_ON_HOLD_BY_HEADSET:
			case IS_PAUSED:
				mp.start();
				state = State.IS_PLAYING;
				startTimer();
				break;
		}
	}

	/**
	 * Play song at index 1 (i.e. the second) while removing the first.
	 * 
	 * This call is valid in any state.
	 */
	public synchronized void playNext() {
		if (enqueuedSongs.size() > 0) {
			enqueuedSongs.remove(0);
			sendEnqueuedSongs();
			reset();
			play();
		}
	}

	/**
	 * Stop playback.
	 * 
	 * Yields effect only when IS_PLAYING.
	 */
	public synchronized void stop() {
		switch (state) {
			case IS_PLAYING:
				mp.pause();
				state = State.IS_PAUSED;
				stopTimer();
				break;
		}
	}

	/**
	 * Seek to a position.
	 * 
	 * Yields effect always but when IS_STOPPED.
	 * 
	 * @param position
	 */
	public synchronized void seek(int position) {
		switch (state) {
			case IS_ON_HOLD_BY_CALL:
			case IS_ON_HOLD_BY_HEADSET:
			case IS_PLAYING:
			case IS_PAUSED:
				mp.seekTo(position);
				break;
		}
	}

	/**
	 * Reset player state.
	 * 
	 * This call changes the state to IS_STOPPED.
	 * 
	 * Yields effect always but when IS_STOPPED.
	 */
	public synchronized void reset() {
		switch (state) {
			case IS_PLAYING:
				mp.stop();
			case IS_ON_HOLD_BY_CALL:
			case IS_ON_HOLD_BY_HEADSET:
			case IS_PAUSED:
				mp.reset();
				nowPlaying = null;
				state = State.IS_STOPPED;
				stopTimer();
				break;
		}
	}

	/**
	 * Validate if nowPlaying is at index 0.
	 * 
	 * This is a helper function.
	 */
	private synchronized void validate() {
		if (enqueuedSongs.size() == 0) {
			reset();
		}
		else {
			Song first = enqueuedSongs.get(0);
			if (first != nowPlaying) {
				reset();
				play();
			}
		}
	}

	/**
	 * Hold the playback.
	 * 
	 * This function is used when a headset is disconnected while playing, or a
	 * call occurs.
	 * 
	 * @param reason
	 */
	public synchronized void hold(State reason) {
		switch (state) {
			case IS_PLAYING:
				stop();
				state = reason;
		}
	}

	/**
	 * Unhold the playback.
	 * 
	 * Yields effect only when current state matches reason.
	 * 
	 * @see hold()
	 * @param reason
	 */
	public synchronized void unhold(State reason) {
		if (state == reason) {
			play();
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();

		/*
		 * When a song playback has ended, play a next one.
		 */
		mp.setOnCompletionListener(new OnCompletionListener() {
			@Override
			public void onCompletion(MediaPlayer mp) {
				playNext();
			}
		});

		/*
		 * Handle incomming and outcomming calls.
		 * 
		 * When the playback is on and there's an incomming call, or an outgoing
		 * is being dialed we want to hold the playback. Finishing the call will
		 * resume the playback only if the previous reason for holding was a
		 * call.
		 */
		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
				if (state.compareTo(TelephonyManager.EXTRA_STATE_IDLE) == 0) {
					unhold(State.IS_ON_HOLD_BY_CALL);
				}
				else {
					hold(State.IS_ON_HOLD_BY_CALL);
				}
			}
		}, new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED));

		/*
		 * Handle (un)plugging a headset
		 * 
		 * When the playback is on and a connected headset is unplugged we want
		 * to hold the playback. Plugging back a headset will resume the
		 * playback only if the previous reason for holding was an unplugging.
		 * 
		 * The AudioManager.ACTION_AUDIO_BECOMING_NOISY is for unplugging only
		 * and reacts instantenously.
		 * 
		 * The Intent.ACTION_HEADSET_PLUG has a hardcoded polling time (in the
		 * Android framework) and would lag for about 1s before stopping. The
		 * lag is acceptable when plugging in.
		 */
		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				switch (intent.getIntExtra("state", -1)) {
					case 0: /* unplugged */
						hold(State.IS_ON_HOLD_BY_HEADSET);
						break;
					case 1: /* plugged */
						unhold(State.IS_ON_HOLD_BY_HEADSET);
						break;
				}
			}
		}, new IntentFilter(Intent.ACTION_HEADSET_PLUG));

		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				hold(State.IS_ON_HOLD_BY_HEADSET);
			}
		}, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
		/*
		 * Handle external storage removal
		 */
		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				stop();
				reset();
				enqueuedSongs.clear();
				Toast.makeText(getApplicationContext(), R.string.msg_err_ejected, Toast.LENGTH_LONG)
						.show();
			}
		}, new IntentFilter(Intent.ACTION_MEDIA_EJECT));

		/*
		 * Register request handling.
		 */
		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				sendAvailableSongs();
			}
		}, Request.GetAvailableSongs.getIntentFilter());

		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				sendEnqueuedSongs();
			}
		}, Request.GetEnqueuedSongs.getIntentFilter());

		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				sendState();
			}
		}, Request.GetState.getIntentFilter());

		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				int index = intent.getIntExtra("index", -1);
				enqueueSong((Song) intent.getParcelableExtra("song"), index);
			}
		}, Request.EnqueueSong.getIntentFilter());

		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				moveSong((Song) intent.getParcelableExtra("song"), intent.getIntExtra("offset", 0));
			}
		}, Request.MoveSong.getIntentFilter());

		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				removeSong((Song) intent.getParcelableExtra("song"));
			}
		}, Request.RemoveSong.getIntentFilter());

		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				sendState();
			}
		}, Request.Stop.getIntentFilter());

		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				play();
			}
		}, Request.Play.getIntentFilter());

		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				playNext();
			}
		}, Request.PlayNext.getIntentFilter());

		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				stop();
			}
		}, Request.Stop.getIntentFilter());

		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				seek(intent.getIntExtra("position", 0));
			}
		}, Request.Seek.getIntentFilter());

		Toast.makeText(this, R.string.msg_service_started, Toast.LENGTH_SHORT).show();
	}

	@Override
	public void onDestroy() {
		reset();
		Toast.makeText(this, R.string.msg_service_stopped, Toast.LENGTH_SHORT).show();
		super.onDestroy();
	}

	/**
	 * Check whether external storage is mounted or not.
	 */
	public static boolean isExternalStorageMounted() {
		return android.os.Environment.getExternalStorageState().compareTo(
				android.os.Environment.MEDIA_MOUNTED) == 0;
	}

	/**
	 * Get a list of available songs.
	 * 
	 * The function returns a list of songs stored in the media database from an
	 * external storage (i.e. memory card).
	 * 
	 * @return
	 */
	public Song[] getSongsAvailable() {
		/*
		 * Doing a query() would result in a fatal error when external storage
		 * is missing.
		 * 
		 * So instead, return an empty list when external storage isn't present.
		 */
		if (!isExternalStorageMounted()) {
			Toast.makeText(this, R.string.msg_err_notmounted, Toast.LENGTH_LONG).show();
			return new Song[] {};
		}

		/*
		 * Fixme: Should a Song have more info?
		 */
		ArrayList<Song> list = new ArrayList<Song>();
		Uri uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
		String[] columns = { MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DISPLAY_NAME };
		Cursor c = getContentResolver().query(uri, columns, null, null,
				MediaStore.Audio.Media.DATA + " ASC");

		int dataIndex = c.getColumnIndex(MediaStore.Audio.Media.DATA);
		int displayIndex = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);

		c.moveToFirst();
		do {
			list.add(new Song(c.getString(dataIndex), c.getString(displayIndex)));
		} while (c.moveToNext());
		c.close();

		return list.toArray(new Song[] {});
	}

	/**
	 * Cast a Parcellable[] to Song[].
	 * 
	 * We need to explicitly cast each object when unpacking a Parcellable[].
	 * 
	 * @return
	 */
	public static Song[] parcelableArrayToSongs(Parcelable[] list) {
		Song[] songs = new Song[list.length];
		for (int i = 0; i < list.length; i++) {
			songs[i] = (Song) list[i];
		}
		return songs;
	}
}