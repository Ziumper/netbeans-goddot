package io.ziumper.godot.lsp.process;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Looks at loopback ports. */
public interface PortProbe {

    /** True if something accepts connections on the port. */
    boolean isOpen(int port);

    /** True if the port can be bound, so it can be assigned to a project. */
    boolean isFree(int port);

    /** Real loopback sockets. */
    PortProbe LOOPBACK = new PortProbe() {
        @Override
        public boolean isOpen(int port) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300);
                return true;
            } catch (IOException ex) {
                return false;
            }
        }

        @Override
        public boolean isFree(int port) {
            try (ServerSocket ss = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                return true;
            } catch (IOException ex) {
                return false;
            }
        }
    };
}
