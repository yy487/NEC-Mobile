package org.necmobile;

import android.content.Context;
import android.content.Intent;
import java.io.File;

final class PreparedLaunch {
    final GameLibrary.Game game;
    final int platform;
    final File directory;
    final LaunchMedia media;
    final String warning;
    PreparedLaunch(GameLibrary.Game game, int platform, File directory, LaunchMedia media, String warning) {
        this.game = game; this.platform = platform; this.directory = directory; this.media = media;
        this.warning = warning;
    }
    Intent intent(Context context) {
        Intent intent = new Intent(context, PlayerActivity.class);
        LaunchMedia.Ref first = media.hardDisk != null ? media.hardDisk : media.floppyA;
        intent.putExtra("platform", platform); intent.putExtra("mode", game.mode);
        intent.putExtra("gameId", game.id); intent.putExtra("gameName", game.name);
        intent.putExtra("directory", directory.getAbsolutePath());
        intent.putExtra("resourceWarning", warning);
        intent.putExtra("resumeEnabled", game.resumeEnabled);
        intent.putExtra("displayAspect", game.machineOptions.getOrDefault("display_aspect", "4:3"));
        intent.putExtra("displayOrientation", game.machineOptions.getOrDefault("display_orientation", "AUTO"));
        intent.putExtra("disk", first == null ? "" : game.disk(first.file).file.getAbsolutePath());
        intent.putExtra("secondDisk", media.floppyB == null ? "" : game.disk(media.floppyB.file).file.getAbsolutePath());
        intent.putExtra("firstIndex", first == null ? 0 : first.index);
        intent.putExtra("secondIndex", media.floppyB == null ? 0 : media.floppyB.index);
        return intent;
    }
}
