/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.mina.filter.codec.statemachine;

import java.util.ArrayList;
import java.util.List;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.filterchain.IoFilter.NextFilter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina4.android.log.Mina4Log;

/**
 * Abstract base class for decoder state machines. Calls init() to get the start
 * {@link DecodingState} of the state machine. Calls destroy() when the state
 * machine has reached its end state or when the session is closed.
 * <p>
 * NOTE: The {@link ProtocolDecoderOutput} used by this class when calling
 * {@link DecodingState#decode(IoBuffer, ProtocolDecoderOutput)} buffers decoded
 * messages in a {@link List}. Once the state machine has reached its end state
 * this class will call finishDecode(List, ProtocolDecoderOutput). The
 * implementation will have to take care of writing the decoded messages to the
 * real {@link ProtocolDecoderOutput} used by the configured
 * {@link ProtocolCodecFilter}.
 * </p>
 * 
 * @author <a href="https://github.com/Mr-Jiang/mina4-android">mina4-android</a>
 */
public abstract class DecodingStateMachine implements DecodingState {

	private static final String TAG = DecodingStateMachine.class.getName();

	private final List<Object> childProducts = new ArrayList<>();

	private final ProtocolDecoderOutput childOutput = new ProtocolDecoderOutput() {
		/**
		 * {@inheritDoc}
		 */
		@Override
		public void flush(NextFilter nextFilter, IoSession session) {
			// Do nothing
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void write(Object message) {
			childProducts.add(message);
		}
	};

	private DecodingState currentState;

	private boolean initialized;

	/**
	 * Invoked to initialize this state machine.
	 * 
	 * @return the start {@link DecodingState}.
	 * @throws Exception
	 *             if the initialization failed
	 */
	protected abstract DecodingState init() throws Exception;

	/**
	 * Called once the state machine has reached its end.
	 * 
	 * @param childProducts
	 *            contains the messages generated by each of the
	 *            {@link DecodingState}s which were exposed to the received data
	 *            during the life time of this state machine.
	 * @param out
	 *            the real {@link ProtocolDecoderOutput} used by the
	 *            {@link ProtocolCodecFilter}.
	 * @return the next state if the state machine should resume.
	 * @throws Exception
	 *             if the decoding end failed
	 */
	protected abstract DecodingState finishDecode(List<Object> childProducts, ProtocolDecoderOutput out)
			throws Exception;

	/**
	 * Invoked to destroy this state machine once the end state has been reached
	 * or the session has been closed.
	 * 
	 * @throws Exception
	 *             if the destruction failed
	 */
	protected abstract void destroy() throws Exception;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public DecodingState decode(IoBuffer in, ProtocolDecoderOutput out) throws Exception {
		DecodingState state = getCurrentState();

		final int limit = in.limit();
		int pos = in.position();

		try {
			for (;;) {
				// Wait for more data if all data is consumed.
				if (pos == limit) {
					break;
				}

				DecodingState oldState = state;
				state = state.decode(in, childOutput);

				// If finished, call finishDecode
				if (state == null) {
					return finishDecode(childProducts, out);
				}

				int newPos = in.position();

				// Wait for more data if nothing is consumed and state didn't
				// change.
				if (newPos == pos && oldState == state) {
					break;
				}
				pos = newPos;
			}

			return this;
		} catch (Exception e) {
			state = null;
			throw e;
		} finally {
			this.currentState = state;

			// Destroy if decoding is finished or failed.
			if (state == null) {
				cleanup();
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public DecodingState finishDecode(ProtocolDecoderOutput out) throws Exception {
		DecodingState nextState;
		DecodingState state = getCurrentState();
		try {
			for (;;) {
				DecodingState oldState = state;
				state = state.finishDecode(childOutput);
				if (state == null) {
					// Finished
					break;
				}

				// Exit if state didn't change.
				if (oldState == state) {
					break;
				}
			}
		} catch (Exception e) {
			state = null;
			Mina4Log.d(TAG, "Ignoring the exception caused by a closed session.\n" + e.toString());
		} finally {
			this.currentState = state;
			nextState = finishDecode(childProducts, out);
			if (state == null) {
				cleanup();
			}
		}
		return nextState;
	}

	private void cleanup() {
		if (!initialized) {
			throw new IllegalStateException();
		}

		initialized = false;
		childProducts.clear();
		try {
			destroy();
		} catch (Exception e2) {
			Mina4Log.w(TAG, "Failed to destroy a decoding state machine.\n" + e2.toString());
		}
	}

	private DecodingState getCurrentState() throws Exception {
		DecodingState state = this.currentState;
		if (state == null) {
			state = init();
			initialized = true;
		}
		return state;
	}
}
