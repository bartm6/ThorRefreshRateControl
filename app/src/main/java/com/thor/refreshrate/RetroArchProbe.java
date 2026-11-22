package com.thor.refreshrate;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Loopback-only, read-only query; never sends gameplay controls or external traffic. */
final class RetroArchProbe {
    private static final int PORT = 55355;

    static Status getStatus() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(90);
            InetAddress local = InetAddress.getByName("127.0.0.1");
            byte[] request = "GET_STATUS".getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(request, request.length, local, PORT));
            byte[] buffer = new byte[1024];
            DatagramPacket answer = new DatagramPacket(buffer, buffer.length);
            socket.receive(answer);
            if (!answer.getAddress().isLoopbackAddress()) return null;
            return Status.parse(new String(answer.getData(), 0,
                    answer.getLength(), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            // When Network Commands is disabled RetroArch does not answer.
            return null;
        }
    }
    static final class Status {
        final boolean loaded;
        final String idPart;
        final String title;

        private Status(boolean loaded, String idPart, String title) {
            this.loaded = loaded;
            this.idPart = idPart;
            this.title = title;
        }

        static Status parse(String message) {
            if (message == null) return null;
            String status = message.trim();
            if (status.equals("GET_STATUS CONTENTLESS")) return new Status(false, null, null);
            if (!status.startsWith("GET_STATUS PLAYING ")
                    && !status.startsWith("GET_STATUS PAUSED ")) return null;
            String detail = status.substring(status.indexOf(' ', "GET_STATUS ".length()) + 1);
            int comma = detail.indexOf(',');
            if (comma < 1) return new Status(true, null, null);
            String system = detail.substring(0, comma).trim();
            int crc = detail.lastIndexOf(",crc32=");
            String title = (crc > comma ? detail.substring(comma + 1, crc)
                    : detail.substring(comma + 1)).trim();
            if (system.isEmpty() || title.isEmpty()) return new Status(true, null, null);
            String key = system.toLowerCase(Locale.ROOT) + "/" + title.toLowerCase(Locale.ROOT);
            return key.length() > 250 ? new Status(true, null, null)
                    : new Status(true, key, title);
        }
    }

}
