// ymageOSM.java
// (C) 2008 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 12.02.2008 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2006-04-02 22:40:07 +0200 (So, 02 Apr 2006) $
// $LastChangedRevision: 1986 $
// $LastChangedBy: orbiter $
//
// LICENSE
// 
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package de.anomic.yacy.graphics;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Random;

import javax.imageio.ImageIO;

import net.yacy.kelondro.data.meta.DigestURI;
import net.yacy.kelondro.logging.Log;
import net.yacy.visualization.RasterPlotter;

import de.anomic.crawler.CrawlProfile;
import de.anomic.crawler.retrieval.Response;
import de.anomic.http.client.Cache;
import de.anomic.search.Switchboard;

public class OSMTile {
    
    // helper methods to load map images from openstreetmap.org
    
    public static RasterPlotter getCombinedTiles(final tileCoordinates t11) {
        tileCoordinates t00, t10, t20, t01, t21, t02, t12, t22;
        t00 = new tileCoordinates(t11.xtile - 1, t11.ytile - 1, t11.zoom);
        t10 = new tileCoordinates(t11.xtile    , t11.ytile - 1, t11.zoom);
        t20 = new tileCoordinates(t11.xtile + 1, t11.ytile - 1, t11.zoom);
        t01 = new tileCoordinates(t11.xtile - 1, t11.ytile    , t11.zoom);
        t21 = new tileCoordinates(t11.xtile + 1, t11.ytile    , t11.zoom);
        t02 = new tileCoordinates(t11.xtile - 1, t11.ytile + 1, t11.zoom);
        t12 = new tileCoordinates(t11.xtile    , t11.ytile + 1, t11.zoom);
        t22 = new tileCoordinates(t11.xtile + 1, t11.ytile + 1, t11.zoom);
        final RasterPlotter m = new RasterPlotter(768, 768, RasterPlotter.MODE_REPLACE, "FFFFFF");
        BufferedImage bi;
        bi = getSingleTile(t00); if (bi != null) m.insertBitmap(getSingleTile(t00),   0,   0);
        bi = getSingleTile(t10); if (bi != null) m.insertBitmap(getSingleTile(t10), 256,   0);
        bi = getSingleTile(t20); if (bi != null) m.insertBitmap(getSingleTile(t20), 512,   0);
        bi = getSingleTile(t01); if (bi != null) m.insertBitmap(getSingleTile(t01),   0, 256);
        bi = getSingleTile(t11); if (bi != null) m.insertBitmap(getSingleTile(t11), 256, 256);
        bi = getSingleTile(t21); if (bi != null) m.insertBitmap(getSingleTile(t21), 512, 256);
        bi = getSingleTile(t02); if (bi != null) m.insertBitmap(getSingleTile(t02),   0, 512);
        bi = getSingleTile(t12); if (bi != null) m.insertBitmap(getSingleTile(t12), 256, 512);
        bi = getSingleTile(t22); if (bi != null) m.insertBitmap(getSingleTile(t22), 512, 512);
        return m;
    }
    
    public static BufferedImage getSingleTile(final tileCoordinates tile) {
        DigestURI tileURL;
        try {
            tileURL = new DigestURI(tile.url(), null);
        } catch (final MalformedURLException e) {
            return null;
        }
        //System.out.println("*** DEBUG: fetching OSM tile: " + tileURL.toNormalform(true, true));
        byte[] tileb = null;
        try {
            tileb = Cache.getContent(tileURL);
        } catch (IOException e1) {
            Log.logException(e1);
        }
        if (tileb == null) {
            // download resource using the crawler and keep resource in memory if possible
            Response entry = null;
            try {
                entry = Switchboard.getSwitchboard().loader.load(tileURL, false, false, CrawlProfile.CACHE_STRATEGY_IFEXIST);
            } catch (IOException e) {
                Log.logWarning("yamyOSM", "cannot load: " + e.getMessage());
                return null;
            }
            tileb = entry.getContent();
            if (entry == null) return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(tileb));
        } catch (final EOFException e) {
            return null;
        } catch (final IOException e) {
            return null;
        }
    }

    public static final Random r = new Random(System.currentTimeMillis()); // to selet tile server
    public static class tileCoordinates {
        
        int xtile, ytile, zoom;

        public tileCoordinates(final double lat, final double lon, final int zoom) {
            this.zoom = zoom;
            this.xtile = (int) Math.floor((lon + 180) / 360 * (1 << zoom));
            this.ytile = (int) Math.floor((1 - Math.log(Math.tan(lat * Math.PI / 180) + 1 / Math.cos(lat * Math.PI / 180)) / Math.PI) / 2 * (1 << zoom));
        }
        
        public tileCoordinates(final int xtile, final int ytile, final int zoom) {
            this.zoom = zoom;
            this.xtile = xtile;
            this.ytile = ytile;
        }
        
        public String url() {
            char server = (char) ((int)'a' + r.nextInt(3));
            return("http://" + server + ".tile.openstreetmap.org/" + zoom + "/" + xtile + "/" + ytile + ".png");
        }

    }
}