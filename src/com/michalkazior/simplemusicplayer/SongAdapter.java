package com.michalkazior.simplemusicplayer;

import java.io.File;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

public class SongAdapter extends ArrayAdapter<Song> {
	private Song[] songs;
	private LayoutInflater li;

	public SongAdapter(Context context, int textViewResourceId, Song[] objects) {
		super(context, textViewResourceId, objects);
		songs = objects;
		li = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		View v = convertView;
		if (v == null) {
			v = li.inflate(R.layout.listitem, null);
		}
		if (position >= 0 && position < songs.length) {
			Song s = songs[position];
			File f = new File(s.getPath());
			TextView tv1 = (TextView) v.findViewById(R.id.listItemTextView1);
			TextView tv2 = (TextView) v.findViewById(R.id.listItemTextView2);
			tv1.setText(f.getName());
			tv2.setText(f.getParent());
		}
		return v;
	}
}
