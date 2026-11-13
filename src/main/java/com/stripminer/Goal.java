package com.stripminer;

/** An optional target for a mining run: stop after a time, a number of ores/items, or a tunnel length. */
public record Goal(Type type, long amount) {
	public enum Type {
		/** {@code amount} is in game ticks (20 per second). */
		TIME,
		/** Ore blocks mined. */
		ORES,
		/** Resource items picked up from mined ores (e.g. diamonds, raw iron). */
		ITEMS,
		/** Blocks of main tunnel. */
		LENGTH
	}

	public String describe() {
		return switch (type) {
			case TIME -> "mine for " + formatTicks(amount);
			case ORES -> "mine " + amount + " ore blocks";
			case ITEMS -> "collect " + amount + " items from ores";
			case LENGTH -> "dig a " + amount + " block long tunnel";
		};
	}

	public static String formatTicks(long ticks) {
		long s = ticks / 20;
		long h = s / 3600;
		long m = (s % 3600) / 60;
		long sec = s % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
	}

	/**
	 * Parses a duration like "30m", "1h30m", "90s" or "45" (plain number = minutes) into ticks.
	 * Returns -1 if it can't be parsed.
	 */
	public static long parseDuration(String text) {
		text = text.trim().toLowerCase(java.util.Locale.ROOT);
		if (text.matches("\\d+")) {
			return Long.parseLong(text) * 60 * 20;
		}
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)([hms])").matcher(text);
		long seconds = 0;
		int end = 0;
		while (m.find()) {
			if (m.start() != end) {
				return -1;
			}
			long n = Long.parseLong(m.group(1));
			seconds += switch (m.group(2)) {
				case "h" -> n * 3600;
				case "m" -> n * 60;
				default -> n;
			};
			end = m.end();
		}
		return end == text.length() && seconds > 0 ? seconds * 20 : -1;
	}
}
