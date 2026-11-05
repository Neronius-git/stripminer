package com.stripminer;

/** Thrown inside the miner to end the job; the message is shown to the player. */
public class StopException extends RuntimeException {
	public StopException(String message) {
		super(message, null, false, false);
	}
}
