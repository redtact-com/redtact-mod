package com.redtact.live.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class Json {
	public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	public static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

	private Json() {
	}
}
