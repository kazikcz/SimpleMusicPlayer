package com.michalkazior.simplemusicplayer;

import java.util.ArrayList;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnKeyListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;

/**
 * Activity for song searching and enqueueing.
 * 
 * The user may filter songs by a given phrase set. The filter matches songs
 * that contain all typed words.
 */
public class SongList extends Activity {
	private ListView availableSongsListView;
	private EditText filterEditText;
	private Song selectedSong;
	private Song[] allSongs = {};
	private ArrayList<Song> filteredSongs = new ArrayList<Song>();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (!Player.isExternalStorageMounted()) {
			setContentView(R.layout.songlist_notmounted);
			return;
		}

		setContentView(R.layout.songlist);

		availableSongsListView = (ListView) findViewById(R.id.playlistAvailableSongsListView);
		filterEditText = (EditText) findViewById(R.id.playlistFilterEditText);

		registerForContextMenu(availableSongsListView);
		availableSongsListView.setOnItemClickListener(new OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				view.showContextMenu();
			}
		});

		registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				allSongs = Player.parcelableArrayToSongs(intent.getParcelableArrayExtra("songs"));
				updateAvailableSongsListView();
			}
		}, Player.Remote.Reply.AvailableSongs.getIntentFilter());

		filterEditText.setOnKeyListener(new OnKeyListener() {
			@Override
			public boolean onKey(View v, int keyCode, KeyEvent event) {
				if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
					updateAvailableSongsListView();
					InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
					imm.hideSoftInputFromWindow(filterEditText.getWindowToken(), 0);
					return true;
				}
				return false;
			}
		});

		sendBroadcast(Player.Remote.Request.GetAvailableSongs.getIntent());
	}

	/**
	 * Updates the available songs list.
	 * 
	 * Updates the list accordingly to the entered filter phrase in the edit
	 * text box.
	 */
	private void updateAvailableSongsListView() {
		filteredSongs = new ArrayList<Song>();
		String words[] = filterEditText.getText().toString().toLowerCase().split(" ");

		for (Song song : allSongs) {
			String name = song.getPath().toLowerCase();
			boolean matches = true;
			for (String word : words) {
				if (!name.contains(word)) matches = false;
			}
			if (matches) filteredSongs.add(song);
		}

		availableSongsListView.setAdapter(new SongAdapter(this, filteredSongs
				.toArray(new Song[] {})));
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		menu.add(R.string.option_menu_enqueue_all).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						for (Song song : filteredSongs) {
							sendBroadcast(Player.Remote.Request.EnqueueSong.getIntent().putExtra(
									"song", song.spawn()));
						}
						return false;
					}
				});

		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
		AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
		selectedSong = (Song) availableSongsListView.getItemAtPosition(info.position);

		menu.add(R.string.context_menu_play_now).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						Song song = selectedSong.spawn();
						sendBroadcast(Player.Remote.Request.EnqueueSong
								.getIntent()
								.putExtra("song", song)
								.putExtra("index", 0));
						sendBroadcast(Player.Remote.Request.Play.getIntent().putExtra("song", song));
						return false;
					}
				});

		menu.add(R.string.context_menu_play_next).setOnMenuItemClickListener(
				new OnMenuItemClickListener() {
					@Override
					public boolean onMenuItemClick(MenuItem item) {
						sendBroadcast(Player.Remote.Request.EnqueueSong
								.getIntent()
								.putExtra("song", selectedSong.spawn())
								.putExtra("afterPlaying", true));
						return false;
					}
				});

		menu.add(R.string.context_menu_enqueue).setOnMenuItemClickListener(
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
}