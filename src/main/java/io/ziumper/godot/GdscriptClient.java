package io.ziumper.godot;

import org.netbeans.modules.lsp.client.spi.LanguageServerProvider;
import org.openide.util.Lookup;
import java.io.IOException;
import org.netbeans.api.editor.mimelookup.MimeRegistration;
import org.netbeans.modules.lsp.client.spi.LanguageServerProvider;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;

@MimeRegistration(mimeType="text/x-gdscript", service=LanguageServerProvider.class)
public class GdscriptClient implements LanguageServerProvider {

    @Override
    public LanguageServerDescription startServer(Lookup lkp) {
         try {
      ProcessBuilder pb = new ProcessBuilder(
        "/path/to/godot",
        "--path", "/path/to/project",
        "--editor",
        "--headless",
        "--no-window",
        "--lsp-port", "6005"
      );
      Process p = pb.start();
      return LanguageServerDescription.create(p.getInputStream(), p.getOutputStream(), p);
    } catch (IOException ex) {
      Exceptions.printStackTrace(ex);
      return null;
    }
    }
    
}
