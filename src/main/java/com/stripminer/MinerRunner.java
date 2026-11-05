package com.stripminer;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;

/**
 * Runs a {@link StripMineJob} on its own thread in lock-step with the client tick: on every
 * client tick the job gets exactly one turn while the client thread waits, so the job never
 * touches the game concurrently with the client. This lets the mining logic be written as plain
 * sequential code ("mine branch, then look for ores, then walk back") instead of a state machine.
 */
public final class MinerRunner {
	public static final Logger LOG = LoggerFactory.getLogger("stripminer");

	private static final Semaphore workerTurn = new Semaphore(0);
	private static final Semaphore clientTurn = new Semaphore(0);
	private static volatile boolean running;
	private static volatile String cancelReason;
	private static volatile StripMineJob current;

	private MinerRunner() {
	}

	public static boolean isRunning() {
		return running;
	}

	public static StripMineJob current() {
		return current;
	}

	public static synchronized boolean start(StripMineJob job) {
		if (running) {
			return false;
		}
		workerTurn.drainPermits();
		clientTurn.drainPermits();
		cancelReason = null;
		current = job;
		running = true;
		Thread thread = new Thread(() -> {
			String endMessage = null;
			try {
				workerTurn.acquireUninterruptibly();
				if (cancelReason == null) {
					job.run();
					endMessage = "§aFinished.";
				}
			} catch (StopException e) {
				endMessage = "§e" + e.getMessage();
			} catch (Throwable t) {
				LOG.error("Strip miner crashed", t);
				endMessage = "§cError: " + t;
			} finally {
				try {
					job.cleanup();
				} catch (Throwable t) {
					LOG.error("Cleanup failed", t);
				}
				if (endMessage == null && cancelReason != null) {
					endMessage = "§e" + cancelReason;
				}
				sendChat(endMessage);
				current = null;
				running = false;
				clientTurn.release();
			}
		}, "StripMiner");
		thread.setDaemon(true);
		thread.start();
		return true;
	}

	public static void stop(String reason) {
		if (running && cancelReason == null) {
			cancelReason = reason;
		}
	}

	/** Called from the client thread at the start of every client tick. */
	public static void onClientTick(Minecraft mc) {
		if (!running) {
			return;
		}
		if (mc.player == null || mc.level == null || mc.gameMode == null) {
			stop("Stopped: left the world.");
		}
		workerTurn.release();
		clientTurn.acquireUninterruptibly();
	}

	/** Called from the worker thread: hands control back to the client and waits for the next tick. */
	static void yieldTick() {
		clientTurn.release();
		workerTurn.acquireUninterruptibly();
		String reason = cancelReason;
		if (reason != null) {
			throw new StopException(reason);
		}
	}

	static void sendChat(String text) {
		if (text == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("§6[StripMiner]§r " + text));
		}
	}
}
