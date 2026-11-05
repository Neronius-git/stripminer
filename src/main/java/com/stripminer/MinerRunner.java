package com.stripminer;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Runs a {@link StripMineJob} on its own thread in lock-step with the client tick: on every
 * client tick the job gets exactly one turn while the client thread waits, so the job never
 * touches the game concurrently with the client. This lets the mining logic be written as plain
 * sequential code ("mine branch, then look for ores, then walk back") instead of a state machine.
 *
 * <p>The job thread only reads the world and decides what to do. Anything that changes the game
 * (breaking/placing blocks, inventory clicks, chat, screens) must run on Minecraft's main thread
 * (Sodium and the renderer check this), so the job hands those actions to the client thread with
 * {@link #onMain(Runnable)} and waits for them.
 */
public final class MinerRunner {
	public static final Logger LOG = LoggerFactory.getLogger("stripminer");

	private static final Semaphore workerTurn = new Semaphore(0);
	private static final Semaphore clientTurn = new Semaphore(0);
	private static volatile boolean running;
	private static volatile String cancelReason;
	private static volatile StripMineJob current;
	private static volatile Thread worker;
	private static volatile Supplier<?> mainTask;
	private static volatile Object mainResult;
	private static volatile Throwable mainError;

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
				worker = null;
				running = false;
				clientTurn.release();
			}
		}, "StripMiner");
		thread.setDaemon(true);
		worker = thread;
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
		while (true) {
			clientTurn.acquireUninterruptibly();
			Supplier<?> task = mainTask;
			if (task == null) {
				break; // the job finished its turn for this tick
			}
			// The job asked for something to be done on the main thread.
			mainTask = null;
			try {
				mainResult = task.get();
			} catch (Throwable t) {
				mainError = t;
			}
			workerTurn.release();
		}
	}

	/** Runs {@code task} on Minecraft's main thread and returns its result (call from the job). */
	@SuppressWarnings("unchecked")
	public static <T> T callOnMain(Supplier<T> task) {
		if (Thread.currentThread() != worker) {
			return task.get();
		}
		mainTask = task;
		clientTurn.release();
		workerTurn.acquireUninterruptibly();
		Object result = mainResult;
		Throwable error = mainError;
		mainResult = null;
		mainError = null;
		if (error instanceof RuntimeException e) {
			throw e;
		}
		if (error instanceof Error e) {
			throw e;
		}
		if (error != null) {
			throw new RuntimeException(error);
		}
		return (T) result;
	}

	/** Runs {@code action} on Minecraft's main thread and waits for it (call from the job). */
	public static void onMain(Runnable action) {
		callOnMain(() -> {
			action.run();
			return null;
		});
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
		onMain(() -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				mc.player.sendSystemMessage(Component.literal("§6[StripMiner]§r " + text));
			}
		});
	}
}
