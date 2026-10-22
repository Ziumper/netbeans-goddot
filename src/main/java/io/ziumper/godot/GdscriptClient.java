package io.ziumper.godot;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.netbeans.api.editor.mimelookup.MimeRegistration;
import org.netbeans.modules.lsp.client.spi.LanguageServerProvider;
import org.netbeans.modules.lsp.client.spi.ServerRestarter;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.services.LanguageClient;
import org.netbeans.modules.lsp.client.spi.LanguageServerProvider;

/**
 *
 * @author ziumper
 */
@MimeRegistration(mimeType="text/x-gdscript", service=LanguageServerProvider.class)
public class GdscriptClient implements LanguageServerProvider {                                                                         
                                                                                                                                                                                   
                                                                                                                                          
       @Override
    public LanguageServerDescription startServer(Lookup lkp) {
        ServerRestarter restarter = lkp.lookup(ServerRestarter.class);
        int port = 6005; // or read from prefs

        // 1. Godot must be reachable — otherwise return null (no server)
        if (!isPortOpen("127.0.0.1", port)) {
            System.out.println("[GODOT MODULE] Port is not open");
            // Optionally schedule a retry when Godot starts
            scheduleRetry(restarter, port);
            return null;
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = isWindows
            ? List.of("cmd.exe", "/c", "npx", "godot-lsp-stdio-bridge", "--godot-port", String.valueOf(port))
            : List.of("npx", "godot-lsp-stdio-bridge", "--godot-port", String.valueOf(port));

        
        
       
        try {
            System.out.println("[GODOT MODULE] Starting gdscript bridge");
            Process p = new ProcessBuilder(cmd).start();
            return LanguageServerDescription.create(
                    p.getInputStream(),
                    p.getOutputStream(),
                    p
            );
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
            return null;
        }
    }

    private boolean isPortOpen(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 500); // 500ms timeout
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void scheduleRetry(ServerRestarter restarter, int port) {
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                System.out.println("[GODOT MODULE] Scheduling restart");
                if (isPortOpen("127.0.0.1", port)) {
                    restarter.restart();
                } else {
                    scheduleRetry(restarter, port);
                }
            }
        };
        new Timer(true).schedule(task, 3000); // check every 3s
    }                                                                                                                     
  }           