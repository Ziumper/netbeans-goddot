package io.ziumper.godot.project;

import javax.swing.event.ChangeListener;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.api.project.Sources;

/** Exposes the whole project directory as one generic "Godot Scripts" source group. */
final class GodotSources implements Sources {

    private final SourceGroup group;

    GodotSources(SourceGroup group) {
        this.group = group;
    }

    @Override
    public SourceGroup[] getSourceGroups(String type) {
        if (Sources.TYPE_GENERIC.equals(type)) {
            return new SourceGroup[]{group};
        }
        return new SourceGroup[0];
    }

    @Override
    public void addChangeListener(ChangeListener l) {
    }

    @Override
    public void removeChangeListener(ChangeListener l) {
    }
}
