package org.nodel.core;

/* 
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. 
 */

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

import org.nodel.Handler;
import org.nodel.Threads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the server-side part of a TCP-based nodel channel.
 * 
 */
public class ChannelServerSocket {
    
    /**
     * (logging related)
     */
    private static AtomicLong s_instanceCounter = new AtomicLong();

    /**
     * (logging related)
     */
    private long _instance = s_instanceCounter.getAndIncrement();

    /**
     * (logging related)
     */
    private Logger _logger = LoggerFactory.getLogger(String.format("%s_%03d", this.getClass().getName(), _instance));

    /**
     * Instance signal / lock.
     */
    private Object _signal = new Object();

    /**
     * @see getRequestedPort
     */
    private int _requestedPort;

    private boolean _localInterfaceOnly;
    
    /**
     * The requested port (0 means any)
     */
    public int getRequestedPort() {
        return _requestedPort;
    }
    
    /**
     * @see getPort
     */
    private int _port;
    
    /**
     * Actual bound port.
     */
    public int getPort() {
        return _port;
    }
    
    /**
     * (call-back)
     */
    private Handler.H1<Integer> _startedHandler;
    
    /**
     * Set unicast started handler (callback includes port number)
     */
    public void setStartedHandler(Handler.H1<Integer> handler) {
    	_startedHandler = handler;
    }
    
    /**
     * The current channel server handler.
     */
    private Handler.H1<Socket> _channelServerHandler;
    
    /**
     * Attaches a channel server handler 
     * (unicast delegate)
     * (delegate must not block)
     * 
     * @param handler 'null' to clear otherwise 
     */
    public void attachChannelServerHandler(Handler.H1<Socket> handler) {
        synchronized (_signal) {
            if (_channelServerHandler != null && handler != null)
                throw new IllegalArgumentException("Handler is already set; must be cleared first using 'null'.");

            _channelServerHandler = handler;
        }
    } // (method)

    /**
     * Can only be enabled once. 
     * (thread unsafe)
     */
    private volatile boolean _enabled = false;

    /**
     * The server socket. 
     * (thread unsafe)
     */
    private ServerSocket _serverSocket;

    /**
     * The thread object. 
     * (initialised in constructor, will be null if shutdown.) 
     * (thread unsafe)
     */
    private Thread _thread;

    /**
     * @param Use '0' for any port.
     * 
     * @throws IOException
     */
    public ChannelServerSocket(int port) {
        this(port, false);
    }

    public ChannelServerSocket(int port, boolean localInterfaceOnly) {
        _requestedPort = port;
        _localInterfaceOnly = localInterfaceOnly;

        // initialise the thread
        _thread = new Thread(new Runnable() {
        	
            @Override
            public void run() {
                ChannelServerSocket.this.run();
            }
            
        });
        _thread.setName(String.format("%s_%03d", this.getClass().getName(), _instance));
        _thread.setDaemon(true);
    } // (constructor)

    /**
     * Starts this server socket.
     */
    public void start() {
            synchronized (_signal) {
                if (_enabled)
                    throw new IllegalStateException("Already started.");

                if (_thread == null)
                    throw new IllegalStateException("Already shutdown.");

                _enabled = true;

			_thread.start();
        }
    } // (method)

    /**
     * (thread entry-point)
     */
    private void run() {
		if (!ensureServerSocket())
			return;

    	// fire the call-back
    	Handler.handle(_startedHandler, _port);
        
        // enter the main loop
        while (_enabled) {
            try {
                loop();
                
            } catch (Exception exc) {
                if (!_enabled)
                    break;
                
                _logger.warn("Unexpected exception occurred, backing off.", exc);
                
                Threads.safeWait(_signal, 8000);
            }
        } // (while)
        
        _logger.info("Thread run to completion.");

    } // (method)
    
    /**
	 * Will keep trying until a server socket is established.
	 */
	private boolean ensureServerSocket() {
		while (_enabled) {
			ServerSocket candidate = null;
			try {
				// initialise the socket
				if (_localInterfaceOnly) {
					candidate = new ServerSocket();
					candidate.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), _requestedPort));
				} else {
					candidate = new ServerSocket(_requestedPort);
				}
				synchronized (_signal) {
					if (!_enabled) {
						candidate.close();
						return false;
					}
					_serverSocket = candidate;
					candidate = null;
					_port = _serverSocket.getLocalPort();
				}

				_logger.info("Bound to port '" + _port + "'");

				return true;
			} catch (Exception exc) {
				if (candidate != null) {
					try {
						candidate.close();
					} catch (IOException ignored) {
					}
				}
				_logger.warn("Could not establish a server socket; will retry in 15 seconds.", exc);
				
				Threads.safeWait(_signal, 15000);
			}
		}
		return false;
	} // (method)

    InetAddress getBoundAddress() {
        synchronized (_signal) {
            return _serverSocket == null ? null : _serverSocket.getInetAddress();
        }
    }

    boolean isShutdown() {
        synchronized (_signal) {
            return _thread == null && (_serverSocket == null || _serverSocket.isClosed());
        }
    }

	/**
     * 
     * @return false to indicate graceful termination.
     * @throws IOException 
     */
    private void loop() throws IOException {
        Socket socket = _serverSocket.accept();

        synchronized (_signal) {
            if (_channelServerHandler != null)
                _channelServerHandler.handle(socket);
        }
    } // (method)

    /**
     * Permanently shuts down this channel freeing up all resources.
     * (may briefly block)
     * (exception free)
     */
    public void shutdown() {
        Thread thread;
        synchronized (_signal) {
            if (!_enabled)
                return;
            
            _enabled = false;

            // close the socket
            try {
                if (_serverSocket != null)
                    _serverSocket.close();
            } catch (Exception exc) {
                // (must consume)
            }
            
            // notify the thread in case its sleeping
            _signal.notifyAll();

            thread = _thread;
        } // (sync)

        // wait outside the signal lock so an accepted connection can finish its handler
        try {
            thread.join();
        } catch (Exception exc) {
            // (must consume)
        }

        synchronized (_signal) {
            if (_thread == thread)
                _thread = null;
        }
        
    } // (method)

} // (class)
