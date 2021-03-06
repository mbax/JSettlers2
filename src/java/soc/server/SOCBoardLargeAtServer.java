/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * This file Copyright (C) 2012-2013 Jeremy D Monin <jeremy@nand.net>
 * Portions of this file Copyright (C) 2012 Paul Bilnoski <paul@bilnoski.net>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * The maintainer of this program can be reached at jsettlers@nand.net
 **/

package soc.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.Vector;

import soc.game.SOCBoard;
import soc.game.SOCBoardLarge;
import soc.game.SOCDevCardConstants;
import soc.game.SOCFortress;
import soc.game.SOCGame;
import soc.game.SOCGameOption;
import soc.game.SOCPlayer;
import soc.game.SOCScenario;
import soc.game.SOCSettlement;
import soc.game.SOCShip;
import soc.game.SOCVillage;
import soc.game.SOCBoard.BoardFactory;
import soc.util.IntPair;
import soc.util.IntTriple;

/**
 * A subclass of {@link SOCBoardLarge} for the server, to isolate
 * {@link #makeNewBoard(Map)} and simplify that parent class.
 * See SOCBoardLarge for more details.
 * For the board layout geometry, see that class javadoc's "Coordinate System" section.
 *<P>
 * A representation of a larger (up to 127 x 127 hexes) JSettlers board,
 * with an arbitrary mix of land and water tiles.
 * Implements {@link SOCBoard#BOARD_ENCODING_LARGE}.
 * Activated with {@link SOCGameOption} <tt>"PLL"</tt>.
 *<P>
 * A {@link SOCGame} uses this board; the board is not given a reference to the game, to enforce layering
 * and keep the board logic simple.  Game rules should be enforced at the game, not the board.
 * Calling board methods won't change the game state.
 *<P>
 * On this large sea board, there can optionally be multiple "land areas"
 * (groups of islands, or subsets of islands), if {@link #getLandAreasLegalNodes()} != null.
 * Land areas are groups of nodes on land; call {@link #getNodeLandArea(int)} to find a node's land area number.
 * The starting land area is {@link #getStartingLandArea()}, if players must start in a certain area.
 * During board setup, {@link #makeNewBoard(Map)} calls
 * {@link #makeNewBoard_placeHexes(int[], int[], int[], boolean, boolean, int, SOCGameOption, String)}
 * once for each land area.  In some game scenarios, players and the robber can be
 * {@link #getPlayerExcludedLandAreas() excluded} from placing in some land areas.
 *<P>
 * It's also possible for an island to have more than one landarea (as in scenario SC_TTD).
 * In this case you should have a border zone of hexes with no landarea, because the landareas
 * are tracked by their hexes' nodes, not by the hexes, and a node can't be in multiple LAs.
 *<P>
 * <H3> To Add a New Board:</H3>
 * To add a new board layout type, for a new game option or scenario:
 * You'll need to declare all parts of its layout, recognize its
 * scenario or game option in makeNewBoard, and call methods to set up the structure.
 * These layout parts' values can be different for 3, 4, or 6 players.
 *<P>
 * A good example is SC_PIRI "Pirate Islands"; see commits 57073cb, f9623e5, and 112e289,
 * or search this class for the string SC_PIRI.
 *<P>
 * Parts of the layout:
 *<UL>
 * <LI> Its height and width, if not default; set in {@link #getBoardSize(Map, int)}
 * <LI> Its set of land hex types, usually shuffled *
 * <LI> Land hex coordinates *
 * <LI> Dice numbers to place at land hex coordinates, sometimes shuffled
 * <LI> Its set of port types (3:1, 2:1), usually shuffled
 * <LI> Its port edge locations and facing directions
 * <LI> Pirate and/or robber starting coordinate (optional)
 * <LI> If multiple Land Areas, its land area ranges within the array of land hex coordinates
 *</UL>
 * &nbsp; * Some "land areas" may include water hexes, to vary the coastline from game to game. <BR>
 *<P>
 * Some scenarios may add other "layout parts" related to their scenario board layout.
 * For example, scenario {@code _SC_PIRI} adds {@code "PP"} for the path followed by the pirate fleet.
 * See {@link SOCBoardLarge#getAddedLayoutPart(String)}, {@link SOCBoardLarge#setAddedLayoutPart(String, int[])},
 * and the list of added parts documented at the {@link soc.message.SOCBoardLayout2} class javadoc.
 *
 * @author Jeremy D Monin &lt;jeremy@nand.net&gt;
 * @since 2.0.00
 */
public class SOCBoardLargeAtServer extends SOCBoardLarge
{
    private static final long serialVersionUID = 2000L;

    /**
     * For game scenarios such as {@link SOCGameOption#K_SC_FTRI _SC_FTRI},
     * dev cards or other items waiting to be claimed by any player.
     * Otherwise null.
     *<P>
     * Initialized in {@link #startGame_putInitPieces(SOCGame)} based on game options.
     * Accessed with {@link #drawItemFromStack()}.
     *<P>
     * In {@code _SC_FTRI}, each item is a {@link SOCDevCardConstants} card type.
     */
    private Stack<Integer> drawStack;
        // if you add a scenario here that uses drawStack, also update the SOCBoardLarge.drawItemFromStack javadoc.

    /**
     * For game scenario option {@link SOCGameOption#K_SC_PIRI _SC_PIRI},
     * the pirate fleet's position on its path (PP).  Otherwise unused.
     * @see #movePirateHexAlongPath(int)
     */
    private int piratePathIndex;

    /**
     * Create a new Settlers of Catan Board, with the v3 encoding.
     * Called by {@link SOCBoardLargeAtServer.BoardFactoryAtServer#createBoard(Map, boolean, int)}
     * to get the right board size and layout based on game options and optional {@link SOCScenario}.
     * The board will be empty (all hexes are water, no dice numbers on any hex).
     * The layout contents are set up later by calling {@link #makeNewBoard(Map)} when the game is about to begin,
     * see {@link SOCBoardLarge} class javadoc for how the layout is sent to clients.
     *
     * @param gameOpts  Game's options if any, otherwise null
     * @param maxPlayers Maximum players; must be 4 or 6
     * @param boardHeightWidth  Board's height and width.
     *        The constants for default size are {@link #BOARDHEIGHT_LARGE}, {@link #BOARDWIDTH_LARGE}.
     * @throws IllegalArgumentException if <tt>maxPlayers</tt> is not 4 or 6, or <tt>boardHeightWidth</tt> is null
     */
    public SOCBoardLargeAtServer
        (final Map<String,SOCGameOption> gameOpts, final int maxPlayers, final IntPair boardHeightWidth)
        throws IllegalArgumentException
    {
        super(gameOpts, maxPlayers, boardHeightWidth);
        // Nothing special for now at server
    }

    // javadoc inherited from SOCBoardLarge.
    // If this scenario has dev cards or items waiting to be claimed by any player, draw the next item from that stack.
    public Integer drawItemFromStack()
    {
        if ((drawStack == null) || drawStack.isEmpty())
            return null;

        return drawStack.pop();
    }

    /**
     * For game scenario option {@link SOCGameOption#K_SC_PIRI _SC_PIRI},
     * move the pirate fleet's position along its path.
     * Calls {@link SOCBoardLarge#setPirateHex(int, boolean) setPirateHex(newHex, true)}.
     *<P>
     * If the pirate fleet is already defeated (all fortresses recaptured), returns 0.
     *
     * @param numSteps  Number of steps to move along the path
     * @return  new pirate hex coordinate, or 0
     * @throws IllegalStateException if this board doesn't have layout part "PP" for the Pirate Path.
     */
    @Override
    public int movePirateHexAlongPath(final int numSteps)
        throws IllegalStateException
    {
        final int[] path = getAddedLayoutPart("PP");
        if (path == null)
            throw new IllegalStateException();
        if (pirateHex == 0)
            return 0;  // fleet already defeated (all fortresses recaptured)
        int i = piratePathIndex + numSteps;
        while (i >= path.length)
            i -= path.length;
        piratePathIndex = i;
        final int ph = path[i];
        setPirateHex(ph, true);
        return ph;
    }

    ////////////////////////////////////////////
    //
    // Make New Board
    //
    // To add a new board layout, see the class javadoc.
    //


    /**
     * Shuffle the hex tiles and layout a board.
     * This is called at server, but not at client;
     * client instead calls methods such as {@link #setLandHexLayout(int[])}
     * and {@link #setLegalAndPotentialSettlements(Collection, int, HashSet[])},
     * see {@link SOCBoardLarge} class javadoc.
     * @param opts {@link SOCGameOption Game options}, which may affect
     *          tile placement on board, or null.  <tt>opts</tt> must be
     *          the same as passed to constructor, and thus give the same size and layout
     *          (same {@link #getBoardEncodingFormat()}).
     */
    @Override
    public void makeNewBoard(final Map<String, SOCGameOption> opts)
    {
        final SOCGameOption opt_breakClumps = (opts != null ? opts.get("BC") : null);

        SOCGameOption opt = (opts != null ? opts.get(SOCGameOption.K_SC_FOG) : null);
        final boolean hasScenarioFog = (opt != null) && opt.getBoolValue();

        final String scen;  // scenario key, such as SOCScenario.K_SC_4ISL, or empty string
        {
            final SOCGameOption optSC = (opts != null ? opts.get("SC") : null);
            if (optSC != null)
            {
                final String ostr = optSC.getStringValue();
                scen = (ostr != null) ? ostr : "";
            } else {
                scen = "";
            }
        }

        final boolean hasScenario4ISL = scen.equals(SOCScenario.K_SC_4ISL);

        // For scenario boards, use 3-player or 4-player or 6-player layout?
        // Always test maxPl for ==6 or < 4 ; actual value may be 6, 4, 3, or 2.
        final int maxPl;
        if (maxPlayers == 6)
        {
            maxPl = 6;
        } else {
            opt = (opts != null ? opts.get("PL") : null);
            if (opt == null)
                maxPl = 4;
            else if (opt.getIntValue() > 4)
                maxPl = 6;
            else
                maxPl = opt.getIntValue();
        }

        // shuffle and place the land hexes, numbers, and robber:
        // sets robberHex, contents of hexLayout[] and numberLayout[].
        // Adds to landHexLayout and nodesOnLand.
        // Clears cachedGetlandHexCoords.
        // Also checks vs game option BC: Break up clumps of # or more same-type hexes/ports

        final int PORTS_TYPES_MAINLAND[], PORTS_TYPES_ISLANDS[];  // port types, or null if none
        final int PORT_LOC_FACING_MAINLAND[], PORT_LOC_FACING_ISLANDS[];  // port edge locations and facings
            // Either PORTS_TYPES_MAINLAND or PORTS_TYPES_ISLANDS can be null.
            // PORTS_TYPES_MAINLAND will be checked for "clumps" of several adjacent 3-for-1 or 2-for-1
            // ports in makeNewBoard_shufflePorts. PORTS_TYPES_ISLANDS will not be checked.

        if (hasScenario4ISL)
        {
            // Four Islands (SC_4ISL)
            if (maxPl < 4)
            {
                landAreasLegalNodes = new HashSet[5];
                makeNewBoard_placeHexes
                    (FOUR_ISL_LANDHEX_TYPE_3PL, FOUR_ISL_LANDHEX_COORD_3PL, FOUR_ISL_DICENUM_3PL, true, true,
                     FOUR_ISL_LANDHEX_LANDAREA_RANGES_3PL, opt_breakClumps, scen);
                PORTS_TYPES_MAINLAND = FOUR_ISL_PORT_TYPE_3PL;
                PORT_LOC_FACING_MAINLAND = FOUR_ISL_PORT_EDGE_FACING_3PL;
                pirateHex = FOUR_ISL_PIRATE_HEX[0];
            }
            else if (maxPl != 6)
            {
                landAreasLegalNodes = new HashSet[5];
                makeNewBoard_placeHexes
                    (FOUR_ISL_LANDHEX_TYPE_4PL, FOUR_ISL_LANDHEX_COORD_4PL, FOUR_ISL_DICENUM_4PL, true, true,
                     FOUR_ISL_LANDHEX_LANDAREA_RANGES_4PL, opt_breakClumps, scen);
                PORTS_TYPES_MAINLAND = FOUR_ISL_PORT_TYPE_4PL;
                PORT_LOC_FACING_MAINLAND = FOUR_ISL_PORT_EDGE_FACING_4PL;
                pirateHex = FOUR_ISL_PIRATE_HEX[1];
            } else {
                // Six Islands
                landAreasLegalNodes = new HashSet[7];
                makeNewBoard_placeHexes
                    (FOUR_ISL_LANDHEX_TYPE_6PL, FOUR_ISL_LANDHEX_COORD_6PL, FOUR_ISL_DICENUM_6PL, true, true,
                     FOUR_ISL_LANDHEX_LANDAREA_RANGES_6PL, opt_breakClumps, scen);
                PORTS_TYPES_MAINLAND = FOUR_ISL_PORT_TYPE_6PL;
                PORT_LOC_FACING_MAINLAND = FOUR_ISL_PORT_EDGE_FACING_6PL;
                pirateHex = FOUR_ISL_PIRATE_HEX[2];
            }
            PORT_LOC_FACING_ISLANDS = null;
            PORTS_TYPES_ISLANDS = null;
        }
        else if (scen.equals(SOCScenario.K_SC_TTD))
        {
            // Through The Desert
            final int idx = (maxPl > 4) ? 2 : (maxPl > 3) ? 1 : 0;  // 6-player, 4, or 3-player board

            // Land Area count varies by number of players;
            // 2 + number of small islands.  Array length has + 1 for unused landAreasLegalNodes[0].
            landAreasLegalNodes = new HashSet[1 + 2 + (TTDESERT_LANDHEX_RANGES_SMALL[idx].length / 2)];

            // - Desert strip (not part of any landarea)
            {
                final int[] desertLandHexCoords = TTDESERT_LANDHEX_COORD_DESERT[idx];
                final int[] desertDiceNum = new int[desertLandHexCoords.length];  // all 0
                final int[] desertLandhexType = new int[desertLandHexCoords.length];
                Arrays.fill(desertLandhexType, DESERT_HEX);

                makeNewBoard_placeHexes
                    (desertLandhexType, desertLandHexCoords, desertDiceNum,
                     false, false, 0, null, scen);
            }

            // - Main island (landarea 1 and 2)
            makeNewBoard_placeHexes
                (TTDESERT_LANDHEX_TYPE_MAIN[idx], TTDESERT_LANDHEX_COORD_MAIN[idx], TTDESERT_DICENUM_MAIN[idx],
                 true, true, TTDESERT_LANDHEX_RANGES_MAIN[idx], opt_breakClumps, scen);

            // - Small islands (LA 3 to n)
            makeNewBoard_placeHexes
                (TTDESERT_LANDHEX_TYPE_SMALL[idx], TTDESERT_LANDHEX_COORD_SMALL[idx], TTDESERT_DICENUM_SMALL[idx],
                 true, true, TTDESERT_LANDHEX_RANGES_SMALL[idx], null, scen);

            pirateHex = TTDESERT_PIRATE_HEX[idx];

            PORTS_TYPES_MAINLAND = TTDESERT_PORT_TYPE[idx];
            PORTS_TYPES_ISLANDS = null;
            PORT_LOC_FACING_MAINLAND = TTDESERT_PORT_EDGE_FACING[idx];
            PORT_LOC_FACING_ISLANDS = null;
        }
        else if (scen.equals(SOCScenario.K_SC_PIRI))
        {
            // Pirate Islands
            landAreasLegalNodes = new HashSet[2];
            final int idx = (maxPl > 4) ? 1 : 0;  // 4-player or 6-player board

            // - Large starting island
            makeNewBoard_placeHexes
                (PIR_ISL_LANDHEX_TYPE_MAIN[idx], PIR_ISL_LANDHEX_COORD_MAIN[idx], PIR_ISL_DICENUM_MAIN[idx],
                 false, false, 1, opt_breakClumps, scen);

            // - Pirate islands
            //  (LA # 0: Player can't place there except at their Lone Settlement coordinate within "LS".)
            makeNewBoard_placeHexes
                (PIR_ISL_LANDHEX_TYPE_PIRI[idx], PIR_ISL_LANDHEX_COORD_PIRI[idx], PIR_ISL_DICENUM_PIRI[idx],
                 false, false, 0, opt_breakClumps, scen);

            pirateHex = PIR_ISL_PIRATE_HEX[idx];

            PORTS_TYPES_MAINLAND = PIR_ISL_PORT_TYPE[idx];
            PORTS_TYPES_ISLANDS = null;
            PORT_LOC_FACING_MAINLAND = PIR_ISL_PORT_EDGE_FACING[idx];
            PORT_LOC_FACING_ISLANDS = null;

            setAddedLayoutPart("PP", PIR_ISL_PPATH[idx]);
        }
        else if (scen.equals(SOCScenario.K_SC_FTRI))
        {
            // Forgotten Tribe
            landAreasLegalNodes = new HashSet[2];
            final int idx = (maxPl > 4) ? 1 : 0;  // 4-player or 6-player board

            // - Larger main island
            makeNewBoard_placeHexes
                (FOR_TRI_LANDHEX_TYPE_MAIN[idx], FOR_TRI_LANDHEX_COORD_MAIN[idx], FOR_TRI_DICENUM_MAIN[idx],
                 true, true, 1, opt_breakClumps, scen);

            // - Small outlying islands for tribe
            //  (LA # 0; Player can't place there)
            makeNewBoard_placeHexes
                (FOR_TRI_LANDHEX_TYPE_ISL[idx], FOR_TRI_LANDHEX_COORD_ISL[idx], null,
                 false, false, 0, null, scen);

            pirateHex = FOR_TRI_PIRATE_HEX[idx];

            // Break up ports (opt_breakClumps) for the 6-player board only.
            // The 4-player board doesn't have enough 3-for-1 ports for that to work.
            if (maxPl > 4)
            {
                PORTS_TYPES_MAINLAND = FOR_TRI_PORT_TYPE[idx];  // PORTS_TYPES_MAINLAND breaks clumps
                PORTS_TYPES_ISLANDS = null;
                PORT_LOC_FACING_MAINLAND = FOR_TRI_PORT_EDGE_FACING[idx];
                PORT_LOC_FACING_ISLANDS = null;
            } else {
                PORTS_TYPES_MAINLAND = null;
                PORTS_TYPES_ISLANDS = FOR_TRI_PORT_TYPE[idx];  // PORTS_TYPES_ISLAND doesn't break clumps
                PORT_LOC_FACING_MAINLAND = null;
                PORT_LOC_FACING_ISLANDS = FOR_TRI_PORT_EDGE_FACING[idx];
            }

            setAddedLayoutPart("CE", FOR_TRI_DEV_CARD_EDGES[idx]);
            setAddedLayoutPart("VE", FOR_TRI_SVP_EDGES[idx]);

            // startGame_putInitPieces will set aside dev cards for players to reach "CE" special edges.
        }
        else if (! hasScenarioFog)
        {
            // This is the fallback layout, the large sea board used when no scenario is chosen.
            // Size is BOARDHEIGHT_LARGE by BOARDWIDTH_LARGE for 4 players.
            // For 6 players, there's an extra row of hexes: BOARDHEIGHT_LARGE + 3.

            landAreasLegalNodes = new HashSet[5];  // hardcoded max number of land areas

            // - Mainland:
            makeNewBoard_placeHexes
                ((maxPl > 4) ? makeNewBoard_landHexTypes_v2 : makeNewBoard_landHexTypes_v1,
                 (maxPl > 4) ? LANDHEX_DICEPATH_MAINLAND_6PL : LANDHEX_DICEPATH_MAINLAND_4PL,
                 (maxPl > 4) ? makeNewBoard_diceNums_v2 : makeNewBoard_diceNums_v1,
                 false, true, 1, opt_breakClumps, scen);

            // - Outlying islands:
            makeNewBoard_placeHexes
                ((maxPl > 4) ? LANDHEX_TYPE_ISLANDS_6PL : LANDHEX_TYPE_ISLANDS_4PL,
                 (maxPl > 4) ? LANDHEX_COORD_ISLANDS_ALL_6PL : LANDHEX_COORD_ISLANDS_ALL_4PL,
                 (maxPl > 4) ? LANDHEX_DICENUM_ISLANDS_6PL : LANDHEX_DICENUM_ISLANDS_4PL,
                 true, true,
                 (maxPl > 4) ? LANDHEX_LANDAREA_RANGES_ISLANDS_6PL : LANDHEX_LANDAREA_RANGES_ISLANDS_4PL,
                 null, scen);

            PORTS_TYPES_MAINLAND = (maxPl > 4) ? PORTS_TYPE_V2 : PORTS_TYPE_V1;
            PORTS_TYPES_ISLANDS = (maxPl > 4) ? PORT_TYPE_ISLANDS_6PL : PORT_TYPE_ISLANDS_4PL;
            PORT_LOC_FACING_MAINLAND = (maxPl > 4) ? PORT_EDGE_FACING_MAINLAND_6PL : PORT_EDGE_FACING_MAINLAND_4PL;
            PORT_LOC_FACING_ISLANDS = (maxPl > 4) ? PORT_EDGE_FACING_ISLANDS_6PL : PORT_EDGE_FACING_ISLANDS_4PL;

        } else {
            // hasScenarioFog
            landAreasLegalNodes = new HashSet[( (maxPl == 6) ? 4 : 3 )];

            if (maxPl < 4)
            {
                // - East and West islands:
                makeNewBoard_placeHexes
                    (FOG_ISL_LANDHEX_TYPE_MAIN_3PL, FOG_ISL_LANDHEX_COORD_MAIN_3PL, FOG_ISL_DICENUM_MAIN_3PL,
                     true, true, 1, opt_breakClumps, scen);

                // - "Fog Island" in the middle:
                makeNewBoard_placeHexes
                    (FOG_ISL_LANDHEX_TYPE_FOG, FOG_ISL_LANDHEX_COORD_FOG_3PL, FOG_ISL_DICENUM_FOG_3PL,
                     true, true, 2, null, scen);

                PORTS_TYPES_MAINLAND = FOG_ISL_PORT_TYPE_3PL;
                PORT_LOC_FACING_MAINLAND = FOG_ISL_PORT_EDGE_FACING_3PL;
            }
            else if (maxPl == 4)
            {
                // - East and West islands:
                makeNewBoard_placeHexes
                    (FOG_ISL_LANDHEX_TYPE_MAIN_4PL, FOG_ISL_LANDHEX_COORD_MAIN_4PL, FOG_ISL_DICENUM_MAIN_4PL,
                     true, true, 1, opt_breakClumps, scen);

                // - "Fog Island" in the middle:
                makeNewBoard_placeHexes
                    (FOG_ISL_LANDHEX_TYPE_FOG, FOG_ISL_LANDHEX_COORD_FOG_4PL, FOG_ISL_DICENUM_FOG_4PL,
                     true, true, 2, null, scen);

                PORTS_TYPES_MAINLAND = FOG_ISL_PORT_TYPE_4PL;
                PORT_LOC_FACING_MAINLAND = FOG_ISL_PORT_EDGE_FACING_4PL;
            }
            else  // maxPl == 6
            {
                // - Northern main island:
                makeNewBoard_placeHexes
                    (FOG_ISL_LANDHEX_TYPE_MAIN_6PL, FOG_ISL_LANDHEX_COORD_MAIN_6PL, FOG_ISL_DICENUM_MAIN_6PL,
                     true, true, 1, opt_breakClumps, scen);

                // - "Fog Island" in an arc from southwest to southeast:
                makeNewBoard_placeHexes
                    (FOG_ISL_LANDHEX_TYPE_FOG_6PL, FOG_ISL_LANDHEX_COORD_FOG_6PL, FOG_ISL_DICENUM_FOG_6PL,
                     true, true, 2, opt_breakClumps, scen);

                // - Gold Corners in southwest, southeast
                makeNewBoard_placeHexes
                    (FOG_ISL_LANDHEX_TYPE_GC, FOG_ISL_LANDHEX_COORD_GC, FOG_ISL_DICENUM_GC,
                     false, false, 3, null, scen);

                PORTS_TYPES_MAINLAND = FOG_ISL_PORT_TYPE_6PL;
                PORT_LOC_FACING_MAINLAND = FOG_ISL_PORT_EDGE_FACING_6PL;
            }
            PORTS_TYPES_ISLANDS = null;  // no ports inside fog island's random layout
            PORT_LOC_FACING_ISLANDS = null;
        }

        // - Players must start on mainland
        //   (for fog, the two large islands)
        if (! hasScenario4ISL)  // hasScenario4ISL doesn't require startingLandArea, it remains 0
            startingLandArea = 1;

        // Set up legalRoadEdges:
        initLegalRoadsFromLandNodes();
        initLegalShipEdges();

        // consistency-check land areas
        if (landAreasLegalNodes != null)
        {
            for (int i = 1; i < landAreasLegalNodes.length; ++i)
                if (landAreasLegalNodes[i] == null)
                    throw new IllegalStateException("inconsistent landAreasLegalNodes: null idx " + i);
        }

        // Hide some land hexes behind fog, if the scenario does that
        if (hasScenarioFog)
        {
            final int[] FOGHEXES = (maxPl == 6)
                ? FOG_ISL_LANDHEX_COORD_FOG_6PL
                : ((maxPl < 4) ? FOG_ISL_LANDHEX_COORD_FOG_3PL : FOG_ISL_LANDHEX_COORD_FOG_4PL);
            makeNewBoard_hideHexesInFog(FOGHEXES);
        }

        // Add villages, if the scenario does that
        opt = (opts != null ? opts.get(SOCGameOption.K_SC_CLVI) : null);
        if ((opt != null) && opt.getBoolValue())
        {
            final int[] cl =
                (maxPl == 6) ? SCEN_CLOTH_VILLAGE_AMOUNTS_NODES_DICE_6PL : SCEN_CLOTH_VILLAGE_AMOUNTS_NODES_DICE_4PL;

            setVillageAndClothLayout(cl);  // also sets board's "general supply"
            setAddedLayoutPart("CV", cl);
        }

        if ((PORTS_TYPES_MAINLAND == null) && (PORTS_TYPES_ISLANDS == null))
        {
            return;  // <--- Early return: No ports to place ---
        }

        // check port locations & facings, make sure no overlap with land hexes
        if (PORTS_TYPES_MAINLAND != null)
            makeNewBoard_checkPortLocationsConsistent(PORT_LOC_FACING_MAINLAND);
        if (PORT_LOC_FACING_ISLANDS != null)
            makeNewBoard_checkPortLocationsConsistent(PORT_LOC_FACING_ISLANDS);

        // copy and shuffle the ports, and check vs game option BC
        int[] portTypes_main;
        int[] portTypes_islands;
        if (PORTS_TYPES_MAINLAND != null)
        {
            portTypes_main = new int[PORTS_TYPES_MAINLAND.length];
            System.arraycopy(PORTS_TYPES_MAINLAND, 0, portTypes_main, 0, portTypes_main.length);
            if ((maxPl == 6) || ! hasScenarioFog)
                makeNewBoard_shufflePorts(portTypes_main, opt_breakClumps);
        } else {
            portTypes_main = null;
        }
        if (PORTS_TYPES_ISLANDS != null)
        {
            portTypes_islands = new int[PORTS_TYPES_ISLANDS.length];
            System.arraycopy(PORTS_TYPES_ISLANDS, 0, portTypes_islands, 0, portTypes_islands.length);
            makeNewBoard_shufflePorts(portTypes_islands, null);
        } else {
            portTypes_islands = null;
        }

        // copy port types to beginning of portsLayout[]
        final int pcountMain = (PORTS_TYPES_MAINLAND != null) ? portTypes_main.length : 0;
        portsCount = pcountMain;
        if (PORTS_TYPES_ISLANDS != null)
            portsCount += PORTS_TYPES_ISLANDS.length;
        if ((portsLayout == null) || (portsLayout.length != (3 * portsCount)))
            portsLayout = new int[3 * portsCount];
        if (PORTS_TYPES_MAINLAND != null)
            System.arraycopy(portTypes_main, 0, portsLayout, 0, pcountMain);
        if (PORTS_TYPES_ISLANDS != null)
            System.arraycopy(portTypes_islands, 0,
                portsLayout, pcountMain, portTypes_islands.length);

        // place the ports (hex numbers and facing) within portsLayout[] and nodeIDtoPortType.
        // fill out the ports[] vectors with node coordinates where a trade port can be placed.
        nodeIDtoPortType = new HashMap<Integer, Integer>();

        // - main island(s):
        // i == port type array index
        // j == port edge & facing array index
        final int L = (portTypes_main != null) ? portTypes_main.length : 0;
        if (L > 0)
        {
            for (int i = 0, j = 0; i < L; ++i)
            {
                final int ptype = portTypes_main[i];  // also == portsLayout[i]
                final int edge = PORT_LOC_FACING_MAINLAND[j];
                ++j;
                final int facing = PORT_LOC_FACING_MAINLAND[j];
                ++j;
                final int[] nodes = getAdjacentNodesToEdge_arr(edge);
                placePort(ptype, -1, facing, nodes[0], nodes[1]);
                // portsLayout[i] is set already, from portTypes_main[i]
                portsLayout[i + portsCount] = edge;
                portsLayout[i + (2 * portsCount)] = facing;
            }
        }

        // - outlying islands:
        // i == port type array index
        // j == port edge & facing array index
        if (PORTS_TYPES_ISLANDS != null)
        {
            for (int i = 0, j = 0; i < PORTS_TYPES_ISLANDS.length; ++i)
            {
                final int ptype = portTypes_islands[i];  // also == portsLayout[i+L]
                final int edge = PORT_LOC_FACING_ISLANDS[j];
                ++j;
                final int facing = PORT_LOC_FACING_ISLANDS[j];
                ++j;
                final int[] nodes = getAdjacentNodesToEdge_arr(edge);
                placePort(ptype, -1, facing, nodes[0], nodes[1]);
                // portsLayout[L+i] is set already, from portTypes_islands[i]
                portsLayout[L + i + portsCount] = edge;
                portsLayout[L + i + (2 * portsCount)] = facing;
            }
        }
    }

    /**
     * For {@link #makeNewBoard(Map)}, place the land hexes, number, and robber,
     * after shuffling landHexType[].
     * Sets robberHex, contents of hexLayoutLg[] and numberLayoutLg[].
     * Adds to {@link #landHexLayout} and {@link SOCBoard#nodesOnLand}.
     * Also checks vs game option BC: Break up clumps of # or more same-type hexes/ports
     * (for land hex resource types).
     * If <tt>landAreaNumber</tt> != 0, also adds to {@link #landAreasLegalNodes}.
     * Called from {@link #makeNewBoard(Map)} at server only; client has its board layout sent from the server.
     *<P>
     * For the board layout geometry, see the {@link SOCBoardLarge} class javadoc's "Coordinate System" section.
     *<P>
     * This method does not clear out {@link #hexLayoutLg} or {@link #numberLayoutLg}
     * before it starts placement.  You can call it multiple times to set up multiple
     * areas of land hexes: Call once for each land area.
     *<P>
     * This method clears {@link #cachedGetLandHexCoords} to <tt>null</tt>.
     *
     * @param landHexType  Resource type to place into {@link #hexLayoutLg} for each land hex; will be shuffled.
     *                    Values are {@link #CLAY_HEX}, {@link #DESERT_HEX}, etc.
     *                    There should be no {@link #FOG_HEX} in here; land hexes are hidden by fog later.
     * @param numPath  Coordinates within {@link #hexLayoutLg} (also within {@link #numberLayoutLg}) for each land hex;
     *                    same array length as <tt>landHexType[]</tt>
     * @param number   Numbers to place into {@link #numberLayoutLg} for each land hex;
     *                    array length is <tt>landHexType[].length</tt> minus 1 for each desert in <tt>landHexType[]</tt>.
     *                    If only some land hexes have dice numbers, <tt>number[]</tt> can be shorter; each
     *                    <tt>number[i]</tt> will be placed at <tt>numPath[i]</tt> until <tt>i >= number.length</tt>.
     *                    Can be <tt>null</tt> if none of these land hexes have dice numbers.
     * @param shuffleDiceNumbers  If true, shuffle the dice <tt>number</tt>s before placing along <tt>numPath</tt>.
     *                 Also only if true, calls {@link #makeNewBoard_placeHexes_moveFrequentNumbers(int[], ArrayList)}
     *                 to make sure 6s, 8s aren't adjacent and gold hexes aren't on 6 or 8.
     *                 <tt>number[]</tt> must not be <tt>null</tt>.
     * @param shuffleLandHexes    If true, shuffle <tt>landHexType[]</tt> before placing along <tt>numPath</tt>.
     * @param landAreaNumber  0 unless there will be more than 1 Land Area (group of islands).
     *                    If != 0, updates {@link #landAreasLegalNodes}<tt>[landAreaNumber]</tt>
     *                    with the same nodes added to {@link SOCBoard#nodesOnLand}.
     * @param optBC    Game option "BC" from the options for this board, or <tt>null</tt>.
     * @param scen     Game scenario, such as {@link SOCScenario#K_SC_4ISL}, or "";
     *                 some scenarios might want special distribution of certain hex types.
     * @throws IllegalStateException  if <tt>landAreaNumber</tt> != 0 and either
     *             {@link #landAreasLegalNodes} == null, or not long enough, or
     *             {@link #landAreasLegalNodes}<tt>[landAreaNumber]</tt> != null
     *             because the land area has already been placed.
     * @throws IllegalArgumentException  if <tt>landHexType</tt> contains {@link #FOG_HEX}.
     * @see #makeNewBoard_placeHexes(int[], int[], int[], boolean, boolean, int[], SOCGameOption, String)
     */
    private final void makeNewBoard_placeHexes
        (int[] landHexType, final int[] numPath, final int[] number, final boolean shuffleDiceNumbers,
         final boolean shuffleLandHexes, final int landAreaNumber, final SOCGameOption optBC, final String scen)
        throws IllegalStateException, IllegalArgumentException
    {
        final int[] pathRanges = { landAreaNumber, numPath.length };  // 1 range, uses all of numPath
        makeNewBoard_placeHexes
            (landHexType, numPath, number, shuffleDiceNumbers, shuffleLandHexes, pathRanges, optBC, scen);
    }

    /**
     * For {@link #makeNewBoard(Map)}, place the land hexes, number, and robber
     * for multiple land areas, after shuffling their common landHexType[].
     * Sets robberHex, contents of hexLayoutLg[] and numberLayoutLg[].
     * Adds to {@link #landHexLayout} and {@link SOCBoard#nodesOnLand}.
     * Also checks vs game option BC: Break up clumps of # or more same-type hexes/ports
     * (for land hex resource types).
     * If Land Area Number != 0, also adds to {@link #landAreasLegalNodes}.
     * Called from {@link #makeNewBoard(Map)} at server only; client has its board layout sent from the server.
     *<P>
     * This method does not clear out {@link #hexLayoutLg} or {@link #numberLayoutLg}
     * before it starts placement.  You can call it multiple times to set up multiple
     * areas of land hexes: Call once for each group of Land Areas which shares a numPath and landHexType.
     * For each land area, it updates {@link #landAreasLegalNodes}<tt>[landAreaNumber]</tt>
     * with the same nodes added to {@link SOCBoard#nodesOnLand}.
     *<P>
     * If part of the board will be hidden by {@link #FOG_HEX}, wait before doing that:
     * This method must shuffle and place the unobscured land hexes.
     * After the last call to <tt>makeNewBoard_placeHexes</tt>, call
     * {@link #makeNewBoard_hideHexesInFog(int[])}.
     *<P>
     * This method clears {@link #cachedGetLandHexCoords} to <tt>null</tt>.
     *
     * @param landHexType  Resource type to place into {@link #hexLayoutLg} for each land hex; will be shuffled.
     *                    Values are {@link #CLAY_HEX}, {@link #DESERT_HEX}, etc.
     *                    There should be no {@link #FOG_HEX} in here; land hexes are hidden by fog later.
     *                    For the Fog Island (scenario option {@link SOCGameOption#K_SC_FOG _SC_FOG}),
     *                    one land area contains some water.  So, <tt>landHexType[]</tt> may contain {@link #WATER_HEX}.
     * @param numPath  Coordinates within {@link #hexLayoutLg} (also within {@link #numberLayoutLg}) for each hex to place;
     *                    same array length as {@code landHexType[]}.  May contain {@code WATER_HEX}.
     *                    <BR> {@code landAreaPathRanges[]} tells how to split this array of hex coordinates
     *                    into multiple Land Areas.
     * @param number   Numbers to place into {@link #numberLayoutLg} for each land hex;
     *                    array length is <tt>landHexType[].length</tt> minus 1 for each desert or water in <tt>landHexType[]</tt>
     *                    if every land hex has a dice number.
     *                    If only some land hexes have dice numbers, <tt>number[]</tt> can be shorter; each
     *                    <tt>number[i]</tt> will be placed at <tt>numPath[i]</tt> until <tt>i >= number.length</tt>.
     *                    Can be <tt>null</tt> if none of these land hexes have dice numbers.
     * @param shuffleDiceNumbers  If true, shuffle the dice <tt>number</tt>s before placing along <tt>numPath</tt>.
     *                    <tt>number[]</tt> must not be <tt>null</tt>.
     * @param shuffleLandHexes    If true, shuffle <tt>landHexType[]</tt> before placing along <tt>numPath</tt>.
     * @param landAreaPathRanges  <tt>numPath[]</tt>'s Land Area Numbers, and the size of each land area.
     *                    Array length is 2 x the count of land areas included.
     *                    Index 0 is the first landAreaNumber, index 1 is the length of that land area (number of hexes).
     *                    Index 2 is the next landAreaNumber, index 3 is that one's length, etc.
     *                    The sum of those lengths must equal <tt>numPath.length</tt>.
     *                    Use landarea number 0 for hexes which will not have a land area.
     * @param optBC    Game option "BC" from the options for this board, or <tt>null</tt>.
     * @param scen     Game scenario, such as {@link SOCScenario#K_SC_4ISL}, or "";
     *                 some scenarios might want special distribution of certain hex types.
     * @throws IllegalStateException  if land area number != 0 and either
     *             {@link #landAreasLegalNodes} == null, or not long enough, or any
     *             {@link #landAreasLegalNodes}<tt>[landAreaNumber]</tt> != null
     *             because the land area has already been placed.
     * @throws IllegalArgumentException if <tt>landAreaPathRanges</tt> is null or has an uneven length, <BR>
     *             or if the total length of its land areas != <tt>numPath.length</tt>, <BR>
     *             or if <tt>landHexType</tt> contains {@link #FOG_HEX}, <BR>
     *             or if {@link SOCBoard#makeNewBoard_checkLandHexResourceClumps(Vector, int)}
     *                 finds an invalid or uninitialized hex coordinate (hex type -1)
     * @see #makeNewBoard_placeHexes(int[], int[], int[], boolean, boolean, int, SOCGameOption, String)
     */
    private final void makeNewBoard_placeHexes
        (int[] landHexType, final int[] numPath, int[] number, final boolean shuffleDiceNumbers,
         final boolean shuffleLandHexes, final int[] landAreaPathRanges, final SOCGameOption optBC, final String scen)
        throws IllegalStateException, IllegalArgumentException
    {
        final boolean checkClumps = (optBC != null) && optBC.getBoolValue();
        final int clumpSize = checkClumps ? optBC.getIntValue() : 0;
        boolean clumpsNotOK = checkClumps;
        final boolean hasRobber = ! SOCScenario.K_SC_PIRI.equals(scen);  // all other known scenarios have a robber

        // Validate landAreaPathRanges lengths within numPath
        if ((landAreaPathRanges == null) || ((landAreaPathRanges.length % 2) != 0))
            throw new IllegalArgumentException("landAreaPathRanges: uneven length");
        if (landAreaPathRanges.length <= 2)
        {
            final int L = landAreaPathRanges[1];
            if (L != numPath.length)
                throw new IllegalArgumentException
                    ("landAreaPathRanges: landarea " + landAreaPathRanges[0]
                     + ": range length " + L + " should be " + numPath.length);
        } else {
            int L = 0, i;
            for (i = 1; i < landAreaPathRanges.length; i += 2)
            {
                L += landAreaPathRanges[i];
                if (L > numPath.length)
                    throw new IllegalArgumentException
                        ("landAreaPathRanges: landarea " + landAreaPathRanges[i-1]
                          + ": total range length " + L + " should be " + numPath.length);
            }
            if (L < numPath.length)
                throw new IllegalArgumentException
                    ("landAreaPathRanges: landarea " + landAreaPathRanges[i-3]
                      + ": total range length " + L + " should be " + numPath.length);
        }

        // Shuffle, place, then check layout for clumps:

        if (numPath.length > 0)
            cachedGetLandHexCoords = null;  // invalidate the previous cached set

        do   // will re-do placement until clumpsNotOK is false
        {
            if (shuffleLandHexes)
            {
                // shuffle the land hexes 10x
                for (int j = 0; j < 10; j++)
                {
                    int idx, tmp;
                    for (int i = 0; i < landHexType.length; i++)
                    {
                        // Swap a random hex below the ith hex with the ith hex
                        idx = Math.abs(rand.nextInt() % (landHexType.length - i));
                        if (idx == i)
                            continue;
                        tmp = landHexType[idx];
                        landHexType[idx] = landHexType[i];
                        landHexType[i] = tmp;
                    }
                }
            }

            if (shuffleDiceNumbers)
            {
                // shuffle the dice #s 10x
                for (int j = 0; j < 10; j++)
                {
                    int idx, tmp;
                    for (int i = 0; i < number.length; i++)
                    {
                        idx = Math.abs(rand.nextInt() % (number.length - i));
                        if (idx == i)
                            continue;
                        tmp = number[idx];
                        number[idx] = number[i];
                        number[i] = tmp;
                    }
                }
            }

            // Place the land hexes, robber, dice numbers.
            // If we've shuffled the numbers, track where the
            // 6s and 8s ("red" frequently-rolled numbers) go.
            ArrayList<Integer> redHexes = (shuffleDiceNumbers ? new ArrayList<Integer>() : null);
            int cnt = 0;
            for (int i = 0; i < landHexType.length; i++)
            {
                final int r = numPath[i] >> 8,
                          c = numPath[i] & 0xFF;

                try
                {
                    // place the land hexes
                    hexLayoutLg[r][c] = landHexType[i];

                    // place the robber on the desert
                    if (landHexType[i] == DESERT_HEX)
                    {
                        if (hasRobber)
                            setRobberHex(numPath[i], false);
                        numberLayoutLg[r][c] = -1;
                        // TODO do we want to not set robberHex? or a specific point?
                    }
                    else if (landHexType[i] == WATER_HEX)
                    {
                        numberLayoutLg[r][c] = 0;  // Fog Island's landarea has some water shuffled in
                    }
                    else if (landHexType[i] == FOG_HEX)
                    {
                        throw new IllegalArgumentException("landHexType can't contain FOG_HEX");
                    }
                    else if ((number != null) && (cnt < number.length))
                    {
                        // place the numbers
                        final int diceNum = number[cnt];
                        numberLayoutLg[r][c] = diceNum;
                        cnt++;

                        if (shuffleDiceNumbers && ((diceNum == 6) || (diceNum == 8)))
                            redHexes.add(numPath[i]);
                    }

                } catch (Exception ex) {
                    throw new IllegalArgumentException
                        ("Problem placing numPath[" + i + "] at 0x"
                         + Integer.toHexString(numPath[i])
                         + " [" + r + "][" + c + "]" + ": " + ex.toString(), ex);
                }

            }  // for (i in landHex)

            if (shuffleLandHexes && checkClumps)
            {
                // Check the newly placed land area(s) for clumps;
                // ones placed in previous method calls are ignored
                Vector<Integer> unvisited = new Vector<Integer>();  // contains each hex's coordinate
                for (int i = 0; i < landHexType.length; ++i)
                    unvisited.addElement(new Integer(numPath[i]));

                clumpsNotOK = makeNewBoard_checkLandHexResourceClumps(unvisited, clumpSize);
            } else {
                clumpsNotOK = false;
            }

            if (shuffleLandHexes && ! clumpsNotOK)
            {
                // Separate adjacent gold hexes.  Does not change numPath or redHexes, only hexLayoutLg.
                //   In scenario SC_TTD, this also makes sure the main island's only GOLD_HEX is placed
                //   within landarea 2 (the small strip past the desert).
                makeNewBoard_placeHexes_arrangeGolds(numPath, landAreaPathRanges, scen);
            }

            if (shuffleDiceNumbers && ! clumpsNotOK)
            {
                // Separate adjacent "red numbers" (6s, 8s)
                //   and make sure gold hex dice aren't too frequent
                makeNewBoard_placeHexes_moveFrequentNumbers(numPath, redHexes);
            }

        } while (clumpsNotOK);

        // Now that we know this layout is okay,
        // add the hex coordinates to landHexLayout,
        // and the hexes' nodes to nodesOnLand.
        // Throws IllegalStateException if landAreaNumber incorrect
        // vs size/contents of landAreasLegalNodes
        // from previously placed land areas.

        for (int i = 0; i < landHexType.length; i++)
            landHexLayout.add(new Integer(numPath[i]));
        for (int i = 0, hexIdx = 0; i < landAreaPathRanges.length; i += 2)
        {
            final int landAreaNumber = landAreaPathRanges[i],
                      landAreaLength = landAreaPathRanges[i + 1],
                      nextHexIdx = hexIdx + landAreaLength;
            makeNewBoard_fillNodesOnLandFromHexes
                (numPath, hexIdx, nextHexIdx, landAreaNumber);
            hexIdx = nextHexIdx;
        }

    }  // makeNewBoard_placeHexes

    /**
     * For {@link #makeNewBoard(Map)}, after placing
     * land hexes and dice numbers into {@link #hexLayoutLg},
     * fine-tune the randomized gold hex placement:
     *<UL>
     * <LI> Find and separate adjacent gold hexes.
     * <LI> For scenario {@link SOCScenario#K_SC_TTD SC_TTD}, ensure the main island's only <tt>GOLD_HEX</tt>
     *      is placed in land area 2, the small strip of land past the desert.
     *</UL>
     *
     * @param hexCoords  All hex coordinates being shuffled; includes gold hexes and non-gold hexes, may include water
     * @param landAreaPathRanges  <tt>numPath[]</tt>'s Land Area Numbers, and the size of each land area;
     *           see this parameter's javadoc at
     *           {@link #makeNewBoard_placeHexes(int[], int[], int[], boolean, boolean, int[], SOCGameOption, String)}.
     * @param scen     Game scenario, such as {@link SOCScenario#K_SC_4ISL}, or "";
     *                 some scenarios might want special distribution of certain hex types.
     */
    private final void makeNewBoard_placeHexes_arrangeGolds
        (final int[] hexCoords, final int[] landAreaPathRanges, final String scen)
    {
        // map of gold hex coords to all their adjacent land hexes, if any;
        // golds with no adjacent land are left out of the map.
        HashMap<Integer, Vector<Integer>> goldAdjac = new HashMap<Integer, Vector<Integer>>();

        // Find each gold hex's adjacent land hexes:
        for (int hex : hexCoords)
        {
            if (GOLD_HEX != getHexTypeFromCoord(hex))
                continue;

            Vector<Integer> adjacLand = getAdjacentHexesToHex(hex, false);
            if (adjacLand == null)
                continue;  // no adjacents, ignore this GOLD_HEX; getAdjacentHexesToHex never returns an empty vector

            goldAdjac.put(Integer.valueOf(hex), adjacLand);
        }

        // Scenario SC_TTD: Special handling for the main island's only gold hex:
        // Make sure that gold's in landarea 2 (small strip past the desert).
        // (For the main island, landAreaPathRanges[] contains landarea 1 and landarea 2.)
        if (scen.equals(SOCScenario.K_SC_TTD)
            && (landAreaPathRanges != null) && (landAreaPathRanges[0] == 1))
        {
            if ((goldAdjac.size() != 1) || (landAreaPathRanges.length != 4))
                throw new IllegalArgumentException("SC_TTD: Main island should have 1 gold hex, 2 landareas");

            final int goldHex = (Integer) (goldAdjac.keySet().toArray()[0]);

            boolean foundInLA2 = false;
            // Search landarea 2 within landHexCoords[] for gold hex coord;
            // landAreaPathRanges[1] == size of LA1 == index of first hex of LA2 within landHexCoords[]
            for (int i = landAreaPathRanges[1]; i < hexCoords.length; ++i)
            {
                if (hexCoords[i] == goldHex)
                {
                    foundInLA2 = true;
                    break;
                }
            }

            if (! foundInLA2)
            {
                // The gold is in landarea 1. Pick a random non-gold hex in landarea 2, and swap hexLayoutLg values.

                final int i = landAreaPathRanges[1] + rand.nextInt(landAreaPathRanges[3]);  // ranges[3] == size of LA2
                final int nonGoldHex = hexCoords[i];

                final int gr = goldHex >> 8,
                          gc = goldHex & 0xFF,
                          nr = nonGoldHex >> 8,
                          nc = nonGoldHex & 0xFF;
                hexLayoutLg[gr][gc] = hexLayoutLg[nr][nc];
                hexLayoutLg[nr][nc] = GOLD_HEX;
            }

            // Will always return from method just past here, because goldAdjac.size is 1.
        }

        if (goldAdjac.size() < 2)
        {
            return;  // <--- Early return: no adjacent gold hexes to check ---
        }

        // See if any adjacents are another gold hex:
        // If so, add both to goldAdjacGold.
        HashMap<Integer, List<Integer>> goldAdjacGold = new HashMap<Integer, List<Integer>>();
        int maxAdjac = 0;
        Integer maxHex = null;

        for (Integer gHex : goldAdjac.keySet())
        {
            for (Integer adjHex : goldAdjac.get(gHex))
            {
                if (goldAdjac.containsKey(adjHex))
                {
                    int n = makeNewBoard_placeHexes_arrGolds_addToAdjacList(goldAdjacGold, gHex, adjHex);
                    if (n > maxAdjac)
                    {
                        maxAdjac = n;
                        maxHex = gHex;
                    }

                    n = makeNewBoard_placeHexes_arrGolds_addToAdjacList(goldAdjacGold, adjHex, gHex);
                    if (n > maxAdjac)
                    {
                        maxAdjac = n;
                        maxHex = adjHex;
                    }
                }
            }
        }

        if (goldAdjacGold.isEmpty())
        {
            return;  // <--- Early return: no adjacent gold hexes ---
        }

        // Build a set of all the other (non-gold) land hexes that aren't adjacent to gold.
        // Then, we'll swap out the gold hex that has the most adjacent golds, in case that
        // was the middle one and no other golds are adjacent.

        HashSet<Integer> nonAdjac = new HashSet<Integer>();
        for (int hex : hexCoords)
        {
            {
                final int htype = getHexTypeFromCoord(hex);
                if ((htype == GOLD_HEX) || (htype == WATER_HEX))
                    continue;
            }

            final Integer hexInt = Integer.valueOf(hex);
            boolean adjac = false;
            for (Vector<Integer> goldAdjacList : goldAdjac.values())
            {
                if (goldAdjacList.contains(hexInt))
                {
                    adjac = true;
                    break;
                }
            }

            if (! adjac)
                nonAdjac.add(hexInt);
        }

        if (nonAdjac.isEmpty())
        {
            return;  // <--- Early return: nowhere non-adjacent to swap it to
        }

        // pick a random nonAdjac, and swap with "middle" gold hex
        makeNewBoard_placeHexes_arrGolds_swapWithRandom
            (maxHex, nonAdjac, goldAdjacGold);

        // if any more, take care of them while we can
        while (! (goldAdjacGold.isEmpty() || nonAdjac.isEmpty()))
        {
            // goldAdjacGold, goldAdjac, and nonAdjac are mutated by swapWithRandom,
            // so we need a new iterator each time.

            final Integer oneGold = goldAdjacGold.keySet().iterator().next();
            makeNewBoard_placeHexes_arrGolds_swapWithRandom
                (oneGold, nonAdjac, goldAdjacGold);
        }
    }

    /**
     * Add hex1 to hex0's adjacency list in this map; create that list if needed.
     * Used by makeNewBoard_placeHexes_arrangeGolds.
     * @param goldAdjacGold  Map from gold hexes to their adjacent gold hexes
     * @param hex0  Hex coordinate that will have a list of adjacents in <tt>goldAdjacGold</tt>
     * @param hex1  Hex coordinate to add to <tt>hex0</tt>'s list
     * @return length of hex0's list after adding hex1
     */
    private final int makeNewBoard_placeHexes_arrGolds_addToAdjacList
        (HashMap<Integer, List<Integer>> goldAdjacGold, final Integer hex0, Integer hex1)
    {
        List<Integer> al = goldAdjacGold.get(hex0);
        if (al == null)
        {
            al = new ArrayList<Integer>();
            goldAdjacGold.put(hex0, al);
        }
        al.add(hex1);
        return al.size();
    }

    /**
     * Swap this gold hex with a random non-adjacent hex in <tt>hexLayoutLg</tt>.
     * Updates <tt>nonAdjac</tt> and <tt>goldAdjacGold</tt>.
     * Used by makeNewBoard_placeHexes_arrangeGolds.
     * @param goldHex  Coordinate of gold hex to swap
     * @param nonAdjac  All land hexes not currently adjacent to a gold hex;
     *                  should not include coordinates of any {@code WATER_HEX}
     * @param goldAdjacGold  Map of golds adjacent to each other
     * @throws IllegalArgumentException  if goldHex coordinates in hexLayoutLg aren't GOLD_HEX
     */
    private final void makeNewBoard_placeHexes_arrGolds_swapWithRandom
        (final Integer goldHex, HashSet<Integer> nonAdjac, HashMap<Integer, List<Integer>> goldAdjacGold)
        throws IllegalArgumentException
    {
        // get a random non-adjacent hex to swap with gold:
        //    not efficient, but won't be called more than once or twice
        //    per board with adjacent golds. Most boards won't have any.
        final Integer nonAdjHex;
        {
            int n = nonAdjac.size();
            Iterator<Integer> nai = nonAdjac.iterator();
            if (n > 1)
                for (n = rand.nextInt(n); n > 0; --n)
                    nai.next();  // skip

            nonAdjHex = nai.next();
        }

        // swap goldHex, nonAdjHex in hexLayoutLg:
        {
            int gr = goldHex >> 8,
                gc = goldHex & 0xFF,
                nr = nonAdjHex >> 8,
                nc = nonAdjHex & 0xFF;
            if (hexLayoutLg[gr][gc] != GOLD_HEX)
                throw new IllegalArgumentException("goldHex coord not gold in hexLayoutLg: 0x" + Integer.toHexString(goldHex));
            hexLayoutLg[gr][gc] = hexLayoutLg[nr][nc];  // gets nonAdjHex's land hex type
            hexLayoutLg[nr][nc] = GOLD_HEX;
        }

        // since it's gold now, remove nonAdjHex and its adjacents from nonAdjac:
        nonAdjac.remove(nonAdjHex);
        Vector<Integer> adjs = getAdjacentHexesToHex(nonAdjHex, false);
        if (adjs != null)
            for (Integer ahex : adjs)
                nonAdjac.remove(ahex);

        // Remove goldHex from goldAdjacGold (both directions):

        // - as value: look for it in its own adjacents' adjacency lists
        final List<Integer> adjacHexesToSwapped = goldAdjacGold.get(goldHex);
        if (adjacHexesToSwapped != null)
        {
            for (Integer ahex : adjacHexesToSwapped)
            {
                List<Integer> adjacToAdjac = goldAdjacGold.get(ahex);
                if (adjacToAdjac != null)
                {
                    adjacToAdjac.remove(goldHex);
                    if (adjacToAdjac.isEmpty())
                        goldAdjacGold.remove(ahex);  // ahex had no other adjacents
                }
            }
        }

        // - as key: remove it from goldAdjacGold
        goldAdjacGold.remove(goldHex);
    }

    /**
     * For {@link #makeNewBoard(Map)}, after placing
     * land hexes and dice numbers into {@link SOCBoardLarge#hexLayoutLg hexLayoutLg}
     * and {@link SOCBoardLarge#numberLayoutLg numberLayoutLg},
     * separate adjacent "red numbers" (6s, 8s)
     * and make sure gold hex dice aren't too frequent.
     * For algorithm details, see comments in this method.
     *<P>
     * Call {@link #makeNewBoard_placeHexes_arrangeGolds(int[], int[], String)} before this
     * method, not after, so that gold hexes will already be in their final locations.
     *<P>
     * If using {@link #FOG_HEX}, no fog should be on the
     * board when calling this method: Don't call after
     * {@link #makeNewBoard_hideHexesInFog(int[])}.
     *
     * @param numPath   Coordinates for each hex being placed; may contain water
     * @param redHexes  Hex coordinates of placed "red" (frequent) dice numbers (6s, 8s)
     * @return  true if able to move all adjacent frequent numbers, false if some are still adjacent.
     * @throws IllegalStateException if a {@link #FOG_HEX} is found
     */
    private final boolean makeNewBoard_placeHexes_moveFrequentNumbers
        (final int[] numPath, ArrayList<Integer> redHexes)
        throws IllegalStateException
    {
        if (redHexes.isEmpty())
            return true;

        // Before anything else, check for frequent gold hexes and
        // swap their numbers with random other hexes in numPath
        // which are less-frequent dice numbers (<= 4 or >= 10).
        {
            ArrayList<Integer> frequentGold = new ArrayList<Integer>();
            for (Integer hexCoord : redHexes)
                if (getHexTypeFromCoord(hexCoord.intValue()) == GOLD_HEX)
                    frequentGold.add(hexCoord);

            if (! frequentGold.isEmpty())
            {
                for (int hex : frequentGold)
                {
                    int swapHex, diceNum;
                    do {
                        swapHex = numPath[Math.abs(rand.nextInt() % (numPath.length - 1))];
                        diceNum = getNumberOnHexFromCoord(swapHex);
                    } while ((swapHex == hex)
                             || (diceNum == 0) || ((diceNum > 4) && (diceNum < 10))
                             || (getHexTypeFromCoord(swapHex) == GOLD_HEX));

                    int hr = hex >> 8,
                        hc = hex & 0xFF,
                        sr = swapHex >> 8,
                        sc = swapHex & 0xFF;
                    numberLayoutLg[sr][sc] = numberLayoutLg[hr][hc];  // gets 6 or 8
                    numberLayoutLg[hr][hc] = diceNum;  // gets 2, 3, 4, 10, 11, or 12

                    redHexes.remove(Integer.valueOf(hex));
                    redHexes.add(swapHex);
                }
            }
        }

        // Overall plan:

        // Make an empty list swappedNums to hold all dice-number swaps, in case we undo them all and retry
        // Duplicate redHexes in case we need to undo all swaps and retry
        //   (This can be deferred until adjacent redHexes are found)
        // numRetries = 0
        // Top of retry loop:
        // Make sets otherCoastalHexes, otherHexes: all land hexes in numPath not adjacent to redHexes
        //   which aren't desert or gold (This can be deferred until adjacent redHexes are found)
        //   otherCoastalHexes holds ones at the edge of the board,
        //   which have fewer adjacent land hexes, and thus less chance of
        //   taking up the last available un-adjacent places
        // Loop through redHexes for 3 or more adjacents in a row
        //   but not in a clump (so, middle one has 2 adjacent reds that aren't next to each other)
        // - If the middle one is moved, the other 2 might have no other adjacents
        //   and not need to move anymore; they could then be removed from redHexes
        // - So, do the Swapping Algorithm on that middle one
        //   (updates redHexes, otherCoastalHexes, otherHexes; see below)
        // - If no swap was available, we should undo all and retry.
        //   (see below)
        // If redHexes is empty, we're done.
        //   Return true.
        // Otherwise, loop through redHexes for each remaining hex
        // - If it has no adjacent reds, remove it from redHexes and loop to next
        // - If it has 1 adjacent red, should swap that adjacent instead;
        //   the algorithm will also remove this one from redHexes
        //   because it will have 0 adjacents after the swap
        // - Do the Swapping Algorithm on it
        //   (updates redHexes, otherCoastalHexes, otherHexes; see below)
        // - If no swap was available, we should undo all and retry.
        //   (see below)
        // Now, if redHexes is empty, we're done.
        //   Return true.
        //
        // If we need to undo all and retry:
        // - ++numRetries
        // - If numRetries > 5, we've tried enough.
        //   Either the swaps we've done will have to do, or caller will make a new board.
        //   Return false.
        // - Otherwise, restore redHexes from the backup we made
        // - Go backwards through the list of swappedNums, reversing each swap
        // - Jump back to "Make sets otherCoastalHexes, otherHexes"

        // Swapping Algorithm for dice numbers:
        //   Returns a triple for swap info (old location, swapped location, delta to index numbers),
        //   or nothing if we failed to swap.
        // - If otherCoastalHexes and otherHexes are empty:
        //   Return nothing.
        // - Pick a random hex from otherCoastalHexes if not empty, otherwise from otherHexes
        // - Swap the numbers and build the swap-info to return
        // - Remove new location and its adjacents from otherCoastalHexes or otherHexes
        // - Remove old location from redHexes
        // - Check each of its adjacent non-red lands, to see if each should be added to otherCoastalHexes or otherHexes
        //     because the adjacent no longer has any adjacent reds
        // - Check each of its adjacent reds, to see if the adjacent no longer has adjacent reds
        //     If so, we won't need to move it: remove from redHexes
        // - Return the swap info.

        // Implementation:

        ArrayList<IntTriple> swappedNums = null;
        ArrayList<Integer> redHexesBk = null;
        int numRetries = 0;
        boolean retry = false;
        do
        {
            HashSet<Integer> otherCoastalHexes = null, otherHexes = null;

            // Loop through redHexes for 3 or more adjacents in a row
            //   but not in a clump (so, middle one has 2 adjacent reds that aren't next to each other)
            // - If the middle one is moved, the other 2 might have no other adjacents
            //   and not need to move anymore; they could then be removed from redHexes
            // - So, do the Swapping Algorithm on that middle one

            for (int i = 0; i < redHexes.size(); )
            {
                final int h0 = redHexes.get(i);

                Vector<Integer> ahex = getAdjacentHexesToHex(h0, false);
                if (ahex == null)
                {
                    redHexes.remove(i);
                    continue;  // <--- No adjacent hexes at all: remove ---
                }

                // count and find red adjacent hexes, remove non-reds from ahex
                int adjacRed = 0, hAdjac1 = 0, hAdjacNext = 0;
                for (Iterator<Integer> ahi = ahex.iterator(); ahi.hasNext(); )
                {
                    final int h = ahi.next();
                    final int dnum = getNumberOnHexFromCoord(h);
                    if ((dnum == 6) || (dnum == 8))
                    {
                        ++adjacRed;
                        if (adjacRed == 1)
                            hAdjac1 = h;
                        else
                            hAdjacNext = h;
                    } else {
                        ahi.remove();  // remove from ahex
                    }
                }

                if (adjacRed == 0)
                {
                    redHexes.remove(i);
                    continue;  // <--- No adjacent red hexes: remove ---
                }
                else if (adjacRed == 1)
                {
                    ++i;
                    continue;  // <--- Not in the middle: skip ---
                }

                // Looking for 3 in a row but not in a clump
                // (so, middle one has 2 adjacent reds that aren't next to each other).
                // If the 2+ adjacent reds are next to each other,
                // swapping one will still leave adjacency.

                if ((adjacRed > 3) || isHexAdjacentToHex(hAdjac1, hAdjacNext))
                {
                    ++i;
                    continue;  // <--- Clump, not 3 in a row: skip ---
                }
                else if (adjacRed == 3)
                {
                    // h0 is that middle one only if the 3 adjacent reds
                    // are not adjacent to each other.
                    // We already checked that hAdjac1 and hAdjacNext aren't adjacent,
                    // so check the final one.
                    boolean clump = false;
                    for (int h : ahex)  // ahex.size() == 3
                    {
                        if ((h == hAdjac1) || (h == hAdjacNext))
                            continue;
                        clump = isHexAdjacentToHex(h, hAdjac1) || isHexAdjacentToHex(h, hAdjacNext);
                    }
                    if (clump)
                    {
                        ++i;
                        continue;  // <--- Clump, not 3 in a row: skip ---
                    }
                }

                // h0 is the middle hex.

                // Before swapping, build arrays if we need to.
                if (redHexesBk == null)
                    redHexesBk = new ArrayList<Integer>(redHexes);
                if (otherCoastalHexes == null)
                {
                    otherCoastalHexes = new HashSet<Integer>();
                    otherHexes = new HashSet<Integer>();
                    makeNewBoard_placeHexes_moveFrequentNumbers_buildArrays
                        (numPath, redHexes, otherCoastalHexes, otherHexes);
                }

                // Now, swap.
                IntTriple midSwap = makeNewBoard_placeHexes_moveFrequentNumbers_swapOne
                    (h0, i, redHexes, otherCoastalHexes, otherHexes);
                if (midSwap == null)
                {
                    retry = true;
                    break;
                } else {
                    if (swappedNums == null)
                        swappedNums = new ArrayList<IntTriple>();
                    swappedNums.add(midSwap);
                    if (midSwap.c != 0)
                    {
                        // other hexes were removed from redHexes.
                        // Update our index position with the delta
                        i += midSwap.c;  // c is always negative
                    }
                }

            }  // for (each h0 in redHexes)

            // If redHexes is empty, we're done.
            if (redHexes.isEmpty())
                return true;

            if (! retry)
            {
                // Otherwise, loop through redHexes for each remaining hex
                // - If it has no adjacent reds, remove it from redHexes and loop to next
                // - If it has 1 adjacent red, should swap that adjacent instead;
                //   the algorithm will also remove this one from redHexes
                //   because it will have 0 adjacents after the swap
                // - Do the Swapping Algorithm on it
                //   (updates redHexes, otherCoastalHexes, otherHexes)
                // - If no swap was available, we should undo all and retry.

                while (! redHexes.isEmpty())
                {
                    final int h0 = redHexes.get(0);

                    Vector<Integer> ahex = getAdjacentHexesToHex(h0, false);
                    if (ahex == null)
                    {
                        redHexes.remove(0);
                        continue;  // <--- No adjacent hexes at all: remove ---
                    }

                    // count and find red adjacent hexes
                    int adjacRed = 0, hAdjac1 = 0;
                    for ( final int h : ahex )
                    {
                        final int dnum = getNumberOnHexFromCoord(h);
                        if ((dnum == 6) || (dnum == 8))
                        {
                            ++adjacRed;
                            if (adjacRed == 1)
                                hAdjac1 = h;
                        }
                    }

                    if (adjacRed == 0)
                    {
                        redHexes.remove(0);
                        continue;  // <--- No adjacent red hexes: remove ---
                    }

                    // We'll either swap h0, or its single adjacent red hex hAdjac1.

                    // Before swapping, build arrays if we need to.
                    if (redHexesBk == null)
                        redHexesBk = new ArrayList<Integer>(redHexes);
                    if (otherCoastalHexes == null)
                    {
                        otherCoastalHexes = new HashSet<Integer>();
                        otherHexes = new HashSet<Integer>();
                        makeNewBoard_placeHexes_moveFrequentNumbers_buildArrays
                            (numPath, redHexes, otherCoastalHexes, otherHexes);
                    }

                    // Now, swap.
                    IntTriple hexnumSwap;
                    if (adjacRed > 1)
                    {
                        hexnumSwap = makeNewBoard_placeHexes_moveFrequentNumbers_swapOne
                            (h0, 0, redHexes, otherCoastalHexes, otherHexes);
                    } else {
                        // swap the adjacent instead; the algorithm will also remove this one from redHexes
                        // because it wil have 0 adjacents after the swap
                        final int iAdjac1 = redHexes.indexOf(Integer.valueOf(hAdjac1));
                        hexnumSwap = makeNewBoard_placeHexes_moveFrequentNumbers_swapOne
                            (hAdjac1, iAdjac1, redHexes, otherCoastalHexes, otherHexes);
                    }

                    if (hexnumSwap == null)
                    {
                        retry = true;
                        break;
                    } else {
                        if (swappedNums == null)
                            swappedNums = new ArrayList<IntTriple>();
                        swappedNums.add(hexnumSwap);
                    }

                }  // while (redHexes not empty)
            }

            if (retry)
            {
                // undo all and retry:
                // - ++numRetries
                // - If numRetries > 5, we've tried enough.
                //   Either the swaps we've done will have to do, or caller will make a new board.
                //   Return false.
                // - Otherwise, restore redHexes from the backup we made
                // - Go backwards through the list of swappedNums, reversing each swap

                ++numRetries;
                if (numRetries > 5)
                    return false;

                redHexes = new ArrayList<Integer>(redHexesBk);
                final int L = (swappedNums != null) ? swappedNums.size() : 0;
                for (int i = L - 1; i >= 0; --i)
                {
                    final IntTriple swap = swappedNums.get(i);
                    final int rs = swap.b >> 8,
                              cs = swap.b & 0xFF,
                              ro = swap.a >> 8,
                              co = swap.a & 0xFF,
                              ntmp = numberLayoutLg[ro][co];
                    numberLayoutLg[ro][co] = numberLayoutLg[rs][cs];
                    numberLayoutLg[rs][cs] = ntmp;
                }
            }

        } while (retry);

        return true;
    }

    /**
     * Build sets used for {@link #makeNewBoard_placeHexes_moveFrequentNumbers(int[], ArrayList)}.
     * Together, otherCoastalHexes and otherHexes are all land hexes in numPath not adjacent to redHexes.
     * otherCoastalHexes holds ones at the edge of the board,
     * which have fewer adjacent land hexes, and thus less chance of
     * taking up the last available un-adjacent places.
     * Water, desert, and gold hexes won't be added to either set.
     *
     * @param numPath   Coordinates for each hex being placed; may contain water
     * @param redHexes  Hex coordinates of placed "red" (frequent) dice numbers (6s, 8s)
     * @param otherCoastalHexes  Empty set to build here
     * @param otherHexes         Empty set to build here
     * @throws IllegalStateException if a {@link #FOG_HEX} is found
     */
    private final void makeNewBoard_placeHexes_moveFrequentNumbers_buildArrays
        (final int[] numPath, final ArrayList<Integer> redHexes,
         HashSet<Integer> otherCoastalHexes, HashSet<Integer> otherHexes)
        throws IllegalStateException
    {
        for (int h : numPath)
        {
            // Don't consider water, desert, gold
            {
                final int htype = getHexTypeFromCoord(h);
                if ((htype == WATER_HEX) || (htype == DESERT_HEX) || (htype == GOLD_HEX))
                    continue;
                if (htype == FOG_HEX)
                    // FOG_HEX shouldn't be on the board yet
                    throw new IllegalStateException("Don't call this after placing fog");
            }

            // Don't consider unnumbered or 6s or 8s
            // (check here because some may have already been removed from redHexes)
            {
                final int dnum = getNumberOnHexFromCoord(h);
                if ((dnum <= 0) || (dnum == 6) || (dnum == 8))
                    continue;
            }

            final Vector<Integer> ahex = getAdjacentHexesToHex(h, false);
            boolean hasAdjacentRed = false;
            if (ahex != null)
            {
                for (int ah : ahex)
                {
                    final int dnum = getNumberOnHexFromCoord(ah);
                    if ((dnum == 6) || (dnum == 8))
                    {
                        hasAdjacentRed = true;
                        break;
                    }
                }
            }

            if (! hasAdjacentRed)
            {
                final Integer hInt = Integer.valueOf(h);

                if (isHexCoastline(h))
                    otherCoastalHexes.add(hInt);
                else
                    otherHexes.add(hInt);
            }
        }
    }

    /**
     * The dice-number swapping algorithm for {@link #makeNewBoard_placeHexes_moveFrequentNumbers(int[], ArrayList)}.
     * If we can, pick a hex in otherCoastalHexes or otherHexes, swap and remove <tt>swaphex</tt>
     * from redHexes, then remove/add hexes from/to otherCoastalHexes, otherHexes, redHexes.
     * See comments for details.
     * @param swaphex  Hex coordinate with a frequent number that needs to be swapped
     * @param swapi    Index of <tt>swaphex</tt> within <tt>redHexes</tt>;
     *                 this is for convenience because the caller is iterating through <tt>redHexes</tt>,
     *                 and this method might remove elements below <tt>swapi</tt>.
     *                 See return value "index delta".
     * @param redHexes  Hex coordinates of placed "red" (frequent) dice numbers
     * @param otherCoastalHexes  Land hexes not adjacent to "red" numbers, at the edge of the island,
     *          for swapping dice numbers.  Coastal hexes have fewer adjacent land hexes, and
     *          thus less chance of taking up the last available un-adjacent places when swapped.
     *          Should not contain gold, desert, or water hexes.
     * @param otherHexes   Land hexes not adjacent to "red" numbers, not at the edge of the island.
     *          Should not contain gold, desert, or water hexes.
     * @return The old frequent-number hex coordinate, its swapped coordinate, and the "index delta",
     *         or null if nothing was available to swap.
     *         If the "index delta" != 0, a hex at <tt>redHexes[j]</tt> was removed
     *         for at least one <tt>j &lt; swapi</tt>, and the caller's loop iterating over <tt>redHexes[]</tt>
     *         should adjust its index (<tt>swapi</tt>) so no elements are skipped; the delta is always negative.
     */
    private final IntTriple makeNewBoard_placeHexes_moveFrequentNumbers_swapOne
        (final int swaphex, int swapi, ArrayList<Integer> redHexes,
         HashSet<Integer> otherCoastalHexes, HashSet<Integer> otherHexes)
    {
        // - If otherCoastalHexes and otherHexes are empty:
        //   Return nothing.
        if (otherCoastalHexes.isEmpty() && otherHexes.isEmpty())
            return null;

        // - Pick a random hex (ohex) from otherCoastalHexes if not empty, otherwise from otherHexes
        HashSet<Integer> others = otherCoastalHexes.isEmpty() ? otherHexes : otherCoastalHexes;
        final int ohex;
        {
            final int olen = others.size();
            final int oi = (olen == 1) ? 0 : rand.nextInt(olen);

            // Although this is a linear search,
            // we chose to optimize the sets for
            // lookups since those are more frequent.
            int i = 0, h = 0;
            for (int hex : others)
            {
                if (oi == i)
                {
                    h = hex;
                    break;
                }
                ++i;
            }
            ohex = h;
        }

        // - Swap the numbers and build the swap-info to return
        IntTriple triple = new IntTriple(swaphex, ohex, 0);
        {
            final int rs = swaphex >> 8,
                      cs = swaphex & 0xFF,
                      ro = ohex >> 8,
                      co = ohex & 0xFF,
                      ntmp = numberLayoutLg[ro][co];
            numberLayoutLg[ro][co] = numberLayoutLg[rs][cs];
            numberLayoutLg[rs][cs] = ntmp;
        }

        // - Remove new location and its adjacents from otherCoastalHexes or otherHexes
        others.remove(ohex);
        Vector<Integer> ahex = getAdjacentHexesToHex(ohex, false);
        if (ahex != null)
        {
            for (int h : ahex)
            {
                if (otherCoastalHexes.contains(h))
                    otherCoastalHexes.remove(h);
                if (otherHexes.contains(h))
                    otherHexes.remove(h);
            }
        }

        // - Remove old location from redHexes
        // - Check each of its adjacent non-red lands, to see if each should be added to otherCoastalHexes or otherHexes
        //     because the adjacent no longer has any adjacent reds
        // - Check each of its adjacent reds, to see if the adjacent no longer has adjacent reds
        //     If so, we won't need to move it: remove from redHexes
        redHexes.remove(Integer.valueOf(swaphex));
        ahex = getAdjacentHexesToHex(swaphex, false);
        if (ahex != null)
        {
            int idelta = 0;

            for (int h : ahex)
            {
                final int dnum = getNumberOnHexFromCoord(h);
                final boolean hexIsRed = (dnum == 6) || (dnum == 8);
                boolean hasAdjacentRed = false;
                Vector<Integer> aahex = getAdjacentHexesToHex(h, false);
                if (aahex != null)
                {
                    // adjacents to swaphex's adjacent
                    for (int aah : aahex)
                    {
                        final int aanum = getNumberOnHexFromCoord(aah);
                        if ((aanum == 6) || (aanum == 8))
                        {
                            hasAdjacentRed = true;
                            break;
                        }
                    }
                }

                if (! hasAdjacentRed)
                {
                    final Integer hInt = Integer.valueOf(h);

                    if (hexIsRed)
                    {
                        // no longer has any adjacent reds; we won't need to move it
                        // Remove from redHexes
                        int j = redHexes.indexOf(hInt);
                        if (j != -1)  // if redHexes.contains(h)
                        {
                            redHexes.remove(j);
                            if (j < swapi)
                            {
                                --idelta;
                                --swapi;
                                triple.c = idelta;
                            }
                        }
                    } else {
                        // no longer has any adjacent reds; can add to otherCoastalHexes or otherHexes
                        if (isHexCoastline(h))
                        {
                            if (! otherCoastalHexes.contains(hInt))
                                otherCoastalHexes.add(hInt);
                        } else{
                            if (! otherHexes.contains(hInt))
                                otherHexes.add(hInt);
                        }
                    }
                }
            }
        }

        // - Return the dice-number swap info.
        return triple;
    }

    /**
     * For {@link #makeNewBoard(Map)}, check port locations and facings, and make sure
     * no port overlaps with a land hex.  Each port's edge coordinate has 2 valid perpendicular
     * facing directions, and ports should be on a land/water edge, facing the land side.
     * Call this method after placing all land hexes.
     * @param portsLocFacing  Array of port location edges and "port facing" directions
     *            ({@link SOCBoard#FACING_NE FACING_NE} = 1, etc), such as {@link #PORT_EDGE_FACING_MAINLAND_4PL}.
     *            Each port has 2 consecutive elements: Edge coordinate (0xRRCC), Port Facing (towards land).
     * @throws IllegalArgumentException  If a port's facing direction isn't possible,
     *            or its location causes its water portion to "overlap" land.
     *            Stops with the first error, doesn't keep checking other ports afterwards.
     *            The detail string will be something like:<BR>
     *            Inconsistent layout: Port at index 2 edge 0x803 covers up land hex 0x703 <BR>
     *            Inconsistent layout: Port at index 2 edge 0x803 faces water, not land, hex 0x904 <BR>
     *            Inconsistent layout: Port at index 2 edge 0x802 facing should be NE or SW, not 3
     */
    private final void makeNewBoard_checkPortLocationsConsistent
        (final int[] portsLocFacing)
        throws IllegalArgumentException
    {
        String err = null;

        int i = 0;
        while (i < portsLocFacing.length)
        {
            final int portEdge = portsLocFacing[i++];
            int portFacing = portsLocFacing[i++];

            // make sure port facing direction makes sense for this type of edge
            // similar to code in SOCBoardLarge.getPortFacingFromEdge, with more specific error messages
            {
                final int r = (portEdge >> 8),
                          c = (portEdge & 0xFF);

                // "|" if r is odd
                if ((r % 2) == 1)
                {
                    if ((portFacing != FACING_E) && (portFacing != FACING_W))
                        err = " facing should be E or W";
                }

                // "/" if (r/2,c) is even,odd or odd,even
                else if ((c % 2) != ((r/2) % 2))
                {
                    if ((portFacing != FACING_NW) && (portFacing != FACING_SE))
                        err = " facing should be NW or SE";
                }
                else
                {
                    // "\" if (r/2,c) is odd,odd or even,even
                    if ((portFacing != FACING_NE) && (portFacing != FACING_SW))
                        err = " facing should be NE or SW";
                }

                if (err != null)
                    err += ", not " + portFacing;
            }

            // check edge's land hex in Port Facing direction
            int hex = getAdjacentHexToEdge(portEdge, portFacing);
            if ((err == null) && ((hex == 0) || (getHexTypeFromCoord(hex) == WATER_HEX)))
                err = " faces water, not land, hex 0x" + Integer.toHexString(hex);

            // facing + 3 rotates to "sea" direction from the port's edge
            portFacing += 3;
            if (portFacing > 6)
                portFacing -= 6;
            hex = getAdjacentHexToEdge(portEdge, portFacing);
            if ((err == null) && ((hex != 0) && (getHexTypeFromCoord(hex) != WATER_HEX)))
                  err = " covers up land hex 0x" + Integer.toHexString(hex);

            if (err != null)
                throw new IllegalArgumentException
                  ("Inconsistent layout: Port at index " + (i-2) + " edge 0x" + Integer.toHexString(portEdge) + err);
        }
    }

    /**
     * Calculate the board's legal settlement/city nodes, based on land hexes.
     * All corners of these hexes are legal for settlements/cities.
     * Called from {@link #makeNewBoard_placeHexes(int[], int[], int[], boolean, boolean, int[], SOCGameOption, String)}.
     * Can use all or part of a <tt>landHexCoords</tt> array.
     *<P>
     * Iterative: Can call multiple times, giving different hexes each time.
     * Each call will add those hexes to {@link #nodesOnLand}.
     * If <tt>landAreaNumber</tt> != 0, also adds them to {@link #landAreasLegalNodes}.
     * Call this method once for each land area.
     *<P>
     * Before the first call, clear <tt>nodesOnLand</tt>.
     *
     * @param landHexCoords  Coordinates of a contiguous group of land hexes.
     *                    If <tt>startIdx</tt> and <tt>pastEndIdx</tt> partially use this array,
     *                    only that part needs to be contiguous, the rest of <tt>landHexCoords</tt> is ignored.
     *                    Any hex coordinates here which are {@link #WATER_HEX} are ignored.
     * @param startIdx    First index to use within <tt>landHexCoords[]</tt>; 0 if using the entire array
     * @param pastEndIdx  Just past the last index to use within <tt>landHexCoords[]</tt>;
     *                    If this call uses landHexCoords up through its end, this is <tt>landHexCoords.length</tt>
     * @param landAreaNumber  0 unless there will be more than 1 Land Area (groups of islands).
     *                    If != 0, updates {@link #landAreasLegalNodes}<tt>[landAreaNumber]</tt>
     *                    with the same nodes added to {@link SOCBoard#nodesOnLand}.
     * @see SOCBoardLarge#initLegalRoadsFromLandNodes()
     * @see SOCBoardLarge#initLegalShipEdges()
     * @throws IllegalStateException  if <tt>landAreaNumber</tt> != 0 and either
     *             {@link #landAreasLegalNodes} == null, or not long enough, or
     *             {@link #landAreasLegalNodes}<tt>[landAreaNumber]</tt> != null
     */
    private void makeNewBoard_fillNodesOnLandFromHexes
        (final int landHexCoords[], final int startIdx, final int pastEndIdx, final int landAreaNumber)
        throws IllegalStateException
    {
        if (landAreaNumber != 0)
        {
            if ((landAreasLegalNodes == null)
                || (landAreaNumber >= landAreasLegalNodes.length))
                throw new IllegalStateException("landarea " + landAreaNumber + " out of range");
            if (landAreasLegalNodes[landAreaNumber] != null)
                throw new IllegalStateException("landarea " + landAreaNumber + " already has landAreasLegalNodes");
            landAreasLegalNodes[landAreaNumber] = new HashSet<Integer>();
        }

        for (int i = startIdx; i < pastEndIdx; ++i)
        {
            final int hex = landHexCoords[i];
            if (getHexTypeFromCoord(hex) == WATER_HEX)
                continue;
            final int[] nodes = getAdjacentNodesToHex(hex);
            for (int j = 0; j < 6; ++j)
            {
                final Integer ni = new Integer(nodes[j]);
                nodesOnLand.add(ni);
                if (landAreaNumber != 0)
                    landAreasLegalNodes[landAreaNumber].add(ni);
            }
            // it's ok to add if this set already contains an Integer equal to nodes[j].
        }

    }  // makeNewBoard_makeLegalNodesFromHexes

    /**
     * For {@link #makeNewBoard(Map)}, hide these hexes under {@link #FOG_HEX} to be revealed later.
     * The hexes will be stored in {@link #fogHiddenHexes}; their {@link #hexLayoutLg} and {@link #numberLayoutLg}
     * elements will be set to {@link #FOG_HEX} and -1.
     *<P>
     * To simplify the bot, client, and network, hexes can be hidden only during makeNewBoard,
     * before the board layout is made and sent to the client.
     *
     * @param hexCoords  Coordinates of each hex to hide in the fog
     * @throws IllegalStateException  if any hexCoord is already {@link #FOG_HEX} within {@link #hexLayoutLg}
     * @see #revealFogHiddenHexPrep(int)
     */
    protected void makeNewBoard_hideHexesInFog(final int[] hexCoords)
        throws IllegalStateException
    {
        for (int i = 0; i < hexCoords.length; ++i)
        {
            final int hexCoord = hexCoords[i];
            final int r = hexCoord >> 8,
                      c = hexCoord & 0xFF;
            final int hex = hexLayoutLg[r][c];
            if (hex == FOG_HEX)
                throw new IllegalStateException("Already fog: 0x" + Integer.toHexString(hexCoord));

            fogHiddenHexes.put(new Integer(hexCoord), (hex << 8) | (numberLayoutLg[r][c] & 0xFF));
            hexLayoutLg[r][c] = FOG_HEX;
            numberLayoutLg[r][c] = -1;
        }
    }

    /**
     * Get the board size for
     * {@link BoardFactoryAtServer#createBoard(Map, boolean, int) BoardFactoryAtServer.createBoard}:
     * The default size {@link SOCBoardLarge#BOARDHEIGHT_LARGE BOARDHEIGHT_LARGE} by
     * {@link SOCBoardLarge#BOARDWIDTH_LARGE BOARDWIDTH_LARGE},
     * unless <tt>gameOpts</tt> contains a scenario (<tt>"SC"</tt>) whose layout has a custom height/width.
     * The fallback 6-player layout size is taller,
     * {@link SOCBoardLarge#BOARDHEIGHT_LARGE BOARDHEIGHT_LARGE} + 3 by {@link SOCBoardLarge#BOARDWIDTH_LARGE BOARDWIDTH_LARGE}.
     * @param gameOpts  Game options, or null
     * @param maxPlayers  Maximum players; must be 4 or 6
     * @return encoded size (0xRRCC), the same format as game option {@code "_BHW"}
     * @see SOCBoardLarge#getBoardSize(Map, int)
     */
    private static int getBoardSize(final Map<String, SOCGameOption> gameOpts, final int maxPlayers)
    {
        int heightWidth = 0;
        SOCGameOption scOpt = null;
        if (gameOpts != null)
            scOpt = gameOpts.get("SC");

        if (scOpt != null)
        {
            // Check scenario name; not all scenarios have a custom board size.
            final String sc = scOpt.getStringValue();
            if (sc.equals(SOCScenario.K_SC_4ISL))
            {
                if (maxPlayers == 6)
                    heightWidth = FOUR_ISL_BOARDSIZE_6PL;
                else
                    heightWidth = FOUR_ISL_BOARDSIZE_4PL;
            }
            else if (sc.equals(SOCScenario.K_SC_FOG))
            {
                if (maxPlayers == 6)
                    heightWidth = FOG_ISL_BOARDSIZE_6PL;
                else
                    heightWidth = FOG_ISL_BOARDSIZE_4PL;
            }
            else if (sc.equals(SOCScenario.K_SC_TTD))
            {
                if (maxPlayers == 6)
                    heightWidth = TTDESERT_BOARDSIZE[2];
                else if (maxPlayers >= 4)
                    heightWidth = TTDESERT_BOARDSIZE[1];
                else
                    heightWidth = TTDESERT_BOARDSIZE[0];
            }
            else if (sc.equals(SOCScenario.K_SC_PIRI))
            {
                if (maxPlayers == 6)
                    heightWidth = PIR_ISL_BOARDSIZE[1];
                else
                    heightWidth = PIR_ISL_BOARDSIZE[0];
            }
            else if (sc.equals(SOCScenario.K_SC_FTRI))
            {
                heightWidth = FOR_TRI_BOARDSIZE[(maxPlayers == 6) ? 1 : 0];
            }
            else if (sc.equals(SOCScenario.K_SC_CLVI) && (maxPlayers == 6))
            {
                // For now, _SC_CLVI uses the fallback layout.
                // For 6 players, height includes an extra row of hexes.
                heightWidth = ((BOARDHEIGHT_LARGE + 3) << 8) | BOARDWIDTH_LARGE;
            }
        }
        else if (maxPlayers == 6)
        {
            // No scenario (scOpt == null), so use the fallback board.
            // For 6 players, this has an extra row of hexes.
            heightWidth = ((BOARDHEIGHT_LARGE + 3) << 8) | BOARDWIDTH_LARGE;
        }

        if (heightWidth == 0)
            return (BOARDHEIGHT_LARGE << 8) | BOARDWIDTH_LARGE;
        else
            return heightWidth;
    }

    /**
     * For scenario game option {@link SOCGameOption#K_SC_PIRI _SC_PIRI},
     * get the list of Legal Sea Edges arranged for the players not vacant.
     * Arranged in same player order as the Lone Settlement locations in Added Layout Part {@code "LS"}.
     *
     * @param ga  Game data, for {@link SOCGame#maxPlayers} and {@link SOCGame#isSeatVacant(int)}
     * @param forPN  -1 for all players, or a specific player number
     * @return  Edge data from {@link #PIR_ISL_SEA_EDGES}, containing either
     *          one array when {@code forPN} != -1, or an array for each
     *          player from 0 to {@code ga.maxPlayers}, where vacant players
     *          get empty subarrays of length 0.
     *          <P>
     *          Each player's list is their individual edge coordinates and/or ranges.
     *          Ranges are designated by a pair of positive,negative numbers: 0xC04, -0xC0D
     *          is a range of the valid edges from C04 through C0D inclusive.
     *          <P>
     *          If game doesn't have {@link SOCGameOption#K_SC_PIRI}, returns {@code null}.
     * @see #startGame_putInitPieces(SOCGame)
     */
    public static final int[][] getLegalSeaEdges(final SOCGame ga, final int forPN)
    {
        if (! (ga.hasSeaBoard && ga.isGameOptionSet(SOCGameOption.K_SC_PIRI)))
            return null;

        final int[][] LEGAL_SEA_EDGES = PIR_ISL_SEA_EDGES[(ga.maxPlayers > 4) ? 1 : 0];
        if (forPN != -1)
        {
            final int[][] lse = { LEGAL_SEA_EDGES[forPN] };
            return lse;
        }

        final int[][] lseArranged = new int[ga.maxPlayers][];

        int i = 0;  // iterate i only when player present, to avoid spacing gaps from vacant players
        for (int pn = 0; pn < ga.maxPlayers; ++pn)
        {
            if (ga.isSeatVacant(pn))
            {
                lseArranged[pn] = new int[0];
            } else {
                lseArranged[pn] = LEGAL_SEA_EDGES[i];
                ++i;
            }
        }

        return lseArranged;
    }

    /**
     * For scenario game option {@link SOCGameOption#K_SC_PIRI _SC_PIRI},
     * place each player's initial pieces.  For {@link SOCGameOption#K_SC_FTRI _SC_FTRI},
     * set aside some dev cards to be claimed later at Special Edges.
     * Otherwise do nothing.
     *<P>
     * For _SC_PIRI also calls each player's {@link SOCPlayer#addLegalSettlement(int)}
     * for their Lone Settlement location (adds layout part "LS").
     * Vacant player numbers get 0 for their {@code "LS"} element.
     *<P>
     * Called from {@link SOCGameHandler#startGame(SOCGame)} for those
     * scenario game options; if you need it called for your game, add
     * a check there for your scenario's {@link SOCGameOption}.
     *<P>
     * This is called after {@link #makeNewBoard(Map)} and before
     * {@link SOCGameHandler#getBoardLayoutMessage}.  So if needed,
     * it can call {@link SOCBoardLarge#setAddedLayoutPart(String, int[])}.
     *<P>
     * If ship placement is restricted by the scenario, please call each player's
     * {@link SOCPlayer#setRestrictedLegalShips(int[])} before calling this method,
     * so the legal and potential arrays will be initialized.
     * @see #getLegalSeaEdges(SOCGame, int)
     */
    public void startGame_putInitPieces(SOCGame ga)
    {
        if (ga.isGameOptionSet(SOCGameOption.K_SC_FTRI))
        {
            // Set aside dev cards for players to be given when reaching "CE" Special Edges.

            final int cpn = ga.getCurrentPlayerNumber();
            ga.setCurrentPlayerNumber(-1);  // to call buyDevCard without giving it to a player

            drawStack = new Stack<Integer>();
            final int n = FOR_TRI_DEV_CARD_EDGES[(ga.maxPlayers > 4) ? 1 : 0].length;
            for (int i = 0; i < n; ++i)
                drawStack.push(ga.buyDevCard());

            ga.setCurrentPlayerNumber(cpn);
            return;
        }

        if (! ga.isGameOptionSet(SOCGameOption.K_SC_PIRI))
            return;

        final int gstate = ga.getGameState();
        ga.setGameState(SOCGame.READY);  // prevent ga.putPiece from advancing turn

        final int[] inits = PIR_ISL_INIT_PIECES[(ga.maxPlayers > 4) ? 1 : 0];
        int[] possiLoneSettles = new int[ga.maxPlayers];  // lone possible-settlement node on the way to the island.
            // vacant players will get 0 here, will not get free settlement, ship, or pirate fortress.

        int i = 0;  // iterate i only when player present, to avoid spacing gaps from vacant players
        for (int pn = 0; pn < ga.maxPlayers; ++pn)
        {
            if (ga.isSeatVacant(pn))
                continue;

            SOCPlayer pl = ga.getPlayer(pn);
            ga.putPiece(new SOCSettlement(pl, inits[i], this));  ++i;
            ga.putPiece(new SOCShip(pl, inits[i], this));  ++i;
            ga.putPiece(new SOCFortress(pl, inits[i], this));  ++i;
            possiLoneSettles[pn] = inits[i];  ga.getPlayer(pn).addLegalSettlement(inits[i]);  ++i;
        }
        setAddedLayoutPart("LS", possiLoneSettles);

        ga.setGameState(gstate);
    }


    ////////////////////////////////////////////
    //
    // Sample Layout
    //


    /**
     * Fallback board layout for 4 players: Main island's ports, clockwise from its northwest.
     * Each port has 2 consecutive elements.
     * First: Port edge coordinate, in hex: 0xRRCC.
     * Second: Port Facing direction: {@link SOCBoard#FACING_E FACING_E}, etc.
     *<P>
     * Port Facing is the direction from the port edge, to the land hex touching it
     * which will have 2 nodes where a port settlement/city can be built.
     */
    private static final int PORT_EDGE_FACING_MAINLAND_4PL[] =
    {
        0x0003, FACING_SE,  0x0006, FACING_SW,
        0x0209, FACING_SW,  0x050B, FACING_W,
        0x0809, FACING_NW,  0x0A06, FACING_NW,
        0x0A03, FACING_NE,  0x0702, FACING_E,
        0x0302, FACING_E
    };

    /**
     * Fallback board layout, 4 players: Outlying islands' ports.
     * Each port has 2 elements.
     * First: Coordinate, in hex: 0xRRCC.
     * Second: Facing
     */
    private static final int PORT_EDGE_FACING_ISLANDS_4PL[] =
    {
        0x060E, FACING_NW,   // - northeast island
        0x0A0F, FACING_SW,  0x0E0C, FACING_NW,        // - southeast island
        0x0E06, FACING_SE    // - southwest island
    };

    /**
     * Port types for the 4 outlying-island ports on the 4-player fallback board.
     * For the mainland's port types, use {@link SOCBoard#PORTS_TYPE_V1}.
     */
    private static final int PORT_TYPE_ISLANDS_4PL[] =
    {
        MISC_PORT, SHEEP_PORT, WHEAT_PORT, WOOD_PORT
    };

    /**
     * Fallback board layout for 4 players: Dice-number path (hex coordinates)
     * on the main island, spiraling inward from the shore.
     * The outlying islands have no dice path.
     * For the mainland's dice numbers, see {@link SOCBoard#makeNewBoard_diceNums_v1}.
     * @see #LANDHEX_COORD_MAINLAND
     */
    private static final int LANDHEX_DICEPATH_MAINLAND_4PL[] =
    {
        // clockwise from northwest
        0x0104, 0x0106, 0x0108, 0x0309, 0x050A,
        0x0709, 0x0908, 0x0906, 0x0904, 0x0703,
        0x0502, 0x0303, 0x0305, 0x0307, 0x0508,
        0x0707, 0x0705, 0x0504, 0x0506
    };

    /**
     * Fallback board layout for 4 players: Main island's land hex coordinates, each row west to east.
     * @see #LANDHEX_DICEPATH_MAINLAND_4PL
     */
    private static final int LANDHEX_COORD_MAINLAND[] =
    {
        0x0104, 0x0106, 0x0108,
        0x0303, 0x0305, 0x0307, 0x0309,
        0x0502, 0x0504, 0x0506, 0x0508, 0x050A,
        0x0703, 0x0705, 0x0707, 0x0709,
        0x0904, 0x0906, 0x0908
    };

    /**
     * 4-player fallback board layout: Outlying islands' cloth village node locations and dice numbers
     * for the {@link SOCScenario#K_SC_CLVI} Cloth Villages scenario (until its real layout is ready).
     * Index 0 is the board's "general supply" cloth count.
     * Index 1 is each village's starting cloth count, from {@link SOCVillage#STARTING_CLOTH}.
     * Further indexes are the locations and dice.
     * Paired for each village: [i] = node, [i+1] = dice number.
     * For testing only: An actual cloth village scenario would have a better layout.
     * @see SOCGameOption#K_SC_CLVI
     * @see #setVillageAndClothLayout(int[])
     */
    private static final int SCEN_CLOTH_VILLAGE_AMOUNTS_NODES_DICE_4PL[] =
    {
        SOCVillage.STARTING_GENERAL_CLOTH,  // Board's "general supply" cloth count
        SOCVillage.STARTING_CLOTH,
        0x610, 6,  // SE point of NE island
        0xA0D, 5,  // N point of SE island
        0xE0B, 9,  // SW point of SE island
        0xE05, 4   // midpoint of SW island
    };

    /**
     * Fallback board layout, 4 players: All the outlying islands' land hex coordinates.
     *<P>
     * The first outlying island (land area 2) is upper-right on board.
     * Second island (landarea 3) is lower-right.
     * Third island (landarea 4) is lower-left.
     * @see #LANDHEX_COORD_ISLANDS_EACH
     * @see #LANDHEX_LANDAREA_RANGES_ISLANDS_4PL
     */
    private static final int LANDHEX_COORD_ISLANDS_ALL_4PL[] =
    {
        0x010E, 0x030D, 0x030F, 0x050E, 0x0510,
        0x0B0D, 0x0B0F, 0x0B11, 0x0D0C, 0x0D0E,
        0x0D02, 0x0D04, 0x0F05, 0x0F07
    };

    /**
     * Fallback board layout for 4 players: Each outlying island's land hex coordinates.
     * @see #LANDHEX_COORD_ISLANDS_ALL_4PL
     * @see #LANDHEX_LANDAREA_RANGES_ISLANDS_4PL
     */
    private static final int LANDHEX_COORD_ISLANDS_EACH[][] =
    {
        { 0x010E, 0x030D, 0x030F, 0x050E, 0x0510 },
        { 0x0B0D, 0x0B0F, 0x0B11, 0x0D0C, 0x0D0E },
        { 0x0D02, 0x0D04, 0x0F05, 0x0F07 }
    };

    /**
     * 4-player island hex counts and land area numbers within {@link #LANDHEX_COORD_ISLANDS_ALL_4PL}.
     * Allows us to shuffle them all together ({@link #LANDHEX_TYPE_ISLANDS_4PL}).
     * @see #LANDHEX_COORD_ISLANDS_EACH
     */
    private static final int LANDHEX_LANDAREA_RANGES_ISLANDS_4PL[] =
    {
        2, 5,  // landarea 2 is an island with 5 hexes
        3, 5,  // landarea 3
        4, 4   // landarea 4
    };

    /**
     * Fallback board layout, 4 players: Land hex types for the 3 small islands,
     * to be used with (for the main island) {@link #makeNewBoard_landHexTypes_v1}[].
     */
    private static final int LANDHEX_TYPE_ISLANDS_4PL[] =
    {
        CLAY_HEX, CLAY_HEX, ORE_HEX, ORE_HEX, ORE_HEX,
        SHEEP_HEX, SHEEP_HEX, WHEAT_HEX, WHEAT_HEX, DESERT_HEX,
        WOOD_HEX, WOOD_HEX, GOLD_HEX, GOLD_HEX
    };

    /**
     * Fallback board layout, 4 players: Dice numbers for the outlying islands.
     * These islands have no defined NumPath; as long as the frequently rolled ("red") 6 and 8 aren't
     * adjacent, and as long as GOLD_HEXes have rare numbers, all is OK.
     * To make the islands more attractive, avoids the infrequntly rolled 2 and 12.
     */
    private static final int LANDHEX_DICENUM_ISLANDS_4PL[] =
    {
        5, 4, 6, 3, 8,
        10, 9, 11, 5, 9,
        4, 10, 5  // leave 1 un-numbered, for the DESERT_HEX
    };

    /**
     * Fallback board layout for 6 players: Dice-number path (hex coordinates)
     * on the main island, spiraling inward from the shore.
     * The outlying islands have no dice path.
     * For the mainland's dice numbers, see {@link SOCBoard#makeNewBoard_diceNums_v2}.
     */
    private static final int LANDHEX_DICEPATH_MAINLAND_6PL[] =
    {
        // clockwise inward from western corner
        0x0701, 0x0502, 0x0303, 0x0104, 0x0106, 0x0108, 0x0309, 0x050A,
        0x070B, 0x090A, 0x0B09, 0x0D08, 0x0D06, 0x0D04, 0x0B03, 0x0902,  // end of outside of spiral
        0x0703, 0x0504, 0x0305, 0x0307, 0x0508,
        0x0709, 0x0908, 0x0B07, 0x0B05, 0x0904,  // end of middle layer of spiral
        0x0705, 0x0506, 0x0707, 0x0906
    };

    /**
     * Fallback board layout for 6 players: Main island's ports, clockwise from its western corner (like dice path).
     * Each port has 2 consecutive elements.
     * First: Port edge coordinate, in hex: 0xRRCC.
     * Second: Port Facing direction: {@link SOCBoard#FACING_E FACING_E}, etc.
     *<P>
     * Port Facing is the direction from the port edge, to the land hex touching it
     * which will have 2 nodes where a port settlement/city can be built.
     */
    private static final int PORT_EDGE_FACING_MAINLAND_6PL[] =
    {
        0x0501, FACING_E,   0x0202, FACING_SE,
        0x0006, FACING_SW,  0x0209, FACING_SW,
        0x050B, FACING_W,   0x080B, FACING_NW,
        0x0B0A, FACING_W,   0x0E08, FACING_NW,
        0x0E05, FACING_NE,  0x0C02, FACING_NE,
        0x0800, FACING_NE
    };

    /**
     * 6-player fallback board layout: Outlying islands' cloth village node locations and dice numbers
     * for the {@link SOCScenario#K_SC_CLVI} Cloth Villages scenario (until its real layout is ready).
     * Index 0 is the board's "general supply" cloth count.
     * Index 1 is each village's starting cloth count, from {@link SOCVillage#STARTING_CLOTH}.
     * Further indexes are the locations and dice.
     * Paired for each village: [i] = node, [i+1] = dice number.
     * For testing only: An actual cloth village scenario would have a better layout.
     * @see SOCGameOption#K_SC_CLVI
     * @see #setVillageAndClothLayout(int[])
     */
    private static final int SCEN_CLOTH_VILLAGE_AMOUNTS_NODES_DICE_6PL[] =
    {
        SOCVillage.STARTING_GENERAL_CLOTH,  // Board's "general supply" cloth count
        SOCVillage.STARTING_CLOTH,
        0x020C, 6,  // NW part of NE island
        0x060E, 10, // SE part of NE island
        0x0A0D, 5,  // N point of SE island
        0x0E0B, 9,  // SW point of SE island
        0x1005, 8,  // west on SW island
        0x1009, 4   // east on SW island
    };

    /**
     * Fallback board layout, 6 players: All the outlying islands' land hex coordinates.
     *<P>
     * The first outlying island (land area 2) is upper-right on board.
     * Second island (landarea 3) is lower-right.
     * Third island (landarea 4) is lower-left.
     * @see #LANDHEX_LANDAREA_RANGES_ISLANDS_6PL
     */
    private static final int LANDHEX_COORD_ISLANDS_ALL_6PL[] =
    {
        0x010E, 0x0110, 0x030D, 0x030F, 0x0311, 0x050E, 0x0510, 0x0711,
        0x0B0D, 0x0B0F, 0x0B11, 0x0D0C, 0x0D0E, 0x0D10, 0x0F0F, 0x0F11,
        0x1102, 0x1104, 0x1106, 0x1108, 0x110A
    };

    /**
     * 4-player island hex counts and land area numbers within {@link #LANDHEX_COORD_ISLANDS_ALL_4PL}.
     * Allows us to shuffle them all together ({@link #LANDHEX_TYPE_ISLANDS_6PL}).
     * @see #LANDHEX_COORD_ISLANDS_EACH
     */
    private static final int LANDHEX_LANDAREA_RANGES_ISLANDS_6PL[] =
    {
        2, 8,  // landarea 2 is an island with 8 hexes
        3, 8,  // landarea 3
        4, 5   // landarea 4
    };

    /**
     * Fallback board layout, 6 players: Land hex types for the 3 small islands,
     * to be used with (for the main island) {@link #makeNewBoard_landHexTypes_v2}[].
     */
    private static final int LANDHEX_TYPE_ISLANDS_6PL[] =
    {
        CLAY_HEX, CLAY_HEX, CLAY_HEX, CLAY_HEX, ORE_HEX, ORE_HEX, ORE_HEX, ORE_HEX,
        SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, WHEAT_HEX, WHEAT_HEX, WHEAT_HEX,
        DESERT_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX, GOLD_HEX, GOLD_HEX
    };

    /**
     * Fallback board layout, 6 players: Dice numbers for the outlying islands.
     * These islands have no defined NumPath; as long as the frequently rolled ("red") 6 and 8 aren't
     * adjacent, and as long as GOLD_HEXes have rare numbers, all is OK.
     * To make the islands more attractive, avoids the infrequntly rolled 2 and 12.
     */
    private static final int LANDHEX_DICENUM_ISLANDS_6PL[] =
    {
        3, 3, 4, 4, 5, 5, 5, 6, 6, 6, 8, 8, 8, 9, 9, 9, 10, 10, 11, 11
        // leave 1 un-numbered, for the DESERT_HEX
    };

    /**
     * Fallback board layout, 6 players: Outlying islands' ports.
     * Each port has 2 elements.
     * First: Coordinate, in hex: 0xRRCC.
     * Second: Facing
     */
    private static final int PORT_EDGE_FACING_ISLANDS_6PL[] =
    {
        0x060F, FACING_NE,   // - northeast island
        0x0A0E, FACING_SE,  0x0E0D, FACING_NE,        // - southeast island
        0x1007, FACING_SE    // - southwest island
    };

    /**
     * Port types for the 4 outlying-island ports on the 6-player fallback board.
     * For the mainland's port types, use {@link SOCBoard#PORTS_TYPE_V2}.
     */
    private static final int PORT_TYPE_ISLANDS_6PL[] =
    {
        MISC_PORT, MISC_PORT, CLAY_PORT, WOOD_PORT
    };


    ////////////////////////////////////////////
    //
    // Fog Island scenario Layout
    //   Has 3-player, 4-player, 6-player versions;
    //   FOG_ISL_LANDHEX_TYPE_FOG[] is shared between 3p and 4p.
    //

    //
    // 3-player
    //

    /**
     * Fog Island: Land hex types for the large southwest and northeast islands.
     */
    private static final int FOG_ISL_LANDHEX_TYPE_MAIN_3PL[] =
    {
        // 14 hexes total: southwest, northeast islands are 7 each
        CLAY_HEX, CLAY_HEX, ORE_HEX, ORE_HEX,
        SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX,
        WHEAT_HEX, WHEAT_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX
    };

    /**
     * Fog Island: Land hex coordinates for the large southwest and northeast islands.
     */
    private static final int FOG_ISL_LANDHEX_COORD_MAIN_3PL[] =
    {
        // Southwest island: 7 hexes centered on rows 7, 9, b, d, columns 2-6
        0x0703, 0x0902, 0x0904, 0x0B03, 0x0B05, 0x0D04, 0x0D06,

        // Northeast island: 7 hexes centered on rows 1, 3, 5, 7, columns 9-d
        0x010A, 0x0309, 0x030B, 0x050A, 0x050C, 0x070B, 0x070D
    };

    /**
     * Fog Island: Dice numbers for hexes on the large southwest and northeast islands.
     * No defined NumPath; as long as 6 and 8 aren't adjacent, all is OK.
     */
    private static final int FOG_ISL_DICENUM_MAIN_3PL[] =
    {
        3, 4, 5, 5, 6, 6, 8, 8, 9, 9, 10, 11, 11, 12
    };

    /**
     * Fog Island: Port edges and facings on the large southwest and northeast islands.
     * Each port has 2 elements: Edge coordinate (0xRRCC), Port Facing.
     *<P>
     * Port Facing is the direction from the port edge, to the land hex touching it
     * which will have 2 nodes where a port settlement/city can be built.
     *<P>
     * Each port's type will be {@link #FOG_ISL_PORT_TYPE_3PL}.
     */
    private static final int FOG_ISL_PORT_EDGE_FACING_3PL[] =
    {
        // Southwest island: 4 ports; placing counterclockwise from northwest end
        0x0702, FACING_E,   0x0A01, FACING_NE,
        0x0D03, FACING_E,   0x0E05, FACING_NE,

        // Northeast island: 4 ports; placing counterclockwise from southeast end
        0x060D, FACING_SW,  0x040C, FACING_SW,
        0x010B, FACING_W,   0x0208, FACING_SE
    };

    /**
     * Fog Island: Port types on the large southwest and northeast islands.
     * Since these won't be shuffled, they will line up with {@link #FOG_ISL_PORT_EDGE_FACING_3PL}.
     */
    private static final int FOG_ISL_PORT_TYPE_3PL[] =
    {
        // Southwest island: 4 ports; placing counterclockwise from northwest end
        MISC_PORT, WHEAT_PORT, SHEEP_PORT, MISC_PORT,

        // Northeast island: 4 ports; placing counterclockwise from southeast end
        ORE_PORT, WOOD_PORT, MISC_PORT, CLAY_PORT
    };

    /**
     * Fog Island: Land and water hex types for the fog island.
     * Shared by 3-player and 4-player layouts.
     */
    private static final int FOG_ISL_LANDHEX_TYPE_FOG[] =
    {
        // 12 hexes total (includes 2 water)
        WATER_HEX, WATER_HEX,
        CLAY_HEX, CLAY_HEX, ORE_HEX, ORE_HEX,
        SHEEP_HEX, WHEAT_HEX, WHEAT_HEX, WOOD_HEX,
        GOLD_HEX, GOLD_HEX
    };

    /**
     * Fog Island: Land and water hex coordinates for the fog island.
     * Hex types for the fog island are {@link #FOG_ISL_LANDHEX_TYPE_FOG}.
     */
    private static final int FOG_ISL_LANDHEX_COORD_FOG_3PL[] =
    {
        // Fog island: 12 hexes, from northwest to southeast;
        // the main part of the fog island is a diagonal line
        // of hexes from (1, 4) to (D, A).
        0x0104, 0x0106, 0x0303,
        0x0305, 0x0506, 0x0707, 0x0908, 0x0B09, 0x0D0A,
        0x0B0B, 0x0B0D, 0x0D0C
    };

    /**
     * Fog Island: Dice numbers for hexes on the fog island.
     * No defined NumPath; as long as 6 and 8 aren't adjacent, all is OK.
     */
    private static final int FOG_ISL_DICENUM_FOG_3PL[] =
    {
        // 10 numbered hexes (excludes 2 water)
        3, 3, 4, 5, 6, 8, 9, 10, 11, 12
    };

    //
    // 4-player
    //

    /** Fog Island: Board size for 4 players: Max row 0x10, max col 0x11. */
    private static final int FOG_ISL_BOARDSIZE_4PL = 0x1011;

    /**
     * Fog Island: Land hex types for the large southwest and northeast islands.
     */
    private static final int FOG_ISL_LANDHEX_TYPE_MAIN_4PL[] =
    {
        // 17 hexes total for southwest, northeast islands
        CLAY_HEX, CLAY_HEX, CLAY_HEX, ORE_HEX, ORE_HEX, ORE_HEX,
        SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX,
        WHEAT_HEX, WHEAT_HEX, WHEAT_HEX,
        WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX
    };

    /**
     * Fog Island: Land hex coordinates for the large southwest and northeast islands.
     */
    private static final int FOG_ISL_LANDHEX_COORD_MAIN_4PL[] =
    {
        // Southwest island: 10 hexes centered on rows 5-d, columns 2-8
        0x0502, 0x0703, 0x0902, 0x0904, 0x0B03, 0x0B05, 0x0B07, 0x0D04, 0x0D06,0x0D08,

        // Northeast island: 7 hexes centered on rows 1-7, columns a-e
        0x010A, 0x010C, 0x030B, 0x030D, 0x050C, 0x050E, 0x070D
    };

    /**
     * Fog Island: Dice numbers for hexes on the large southwest and northeast islands.
     * No defined NumPath; as long as 6 and 8 aren't adjacent, all is OK.
     */
    private static final int FOG_ISL_DICENUM_MAIN_4PL[] =
    {
        2, 3, 3, 4, 4, 5, 5, 6, 6, 8, 8, 9, 9, 10, 10, 11, 12
    };

    /**
     * Fog Island: Port edges and facings on the large southwest and northeast islands.
     * Each port has 2 elements: Edge coordinate (0xRRCC), Port Facing.
     *<P>
     * Port Facing is the direction from the port edge, to the land hex touching it
     * which will have 2 nodes where a port settlement/city can be built.
     *<P>
     * Each port's type will be {@link #FOG_ISL_PORT_TYPE_4PL}.
     */
    private static final int FOG_ISL_PORT_EDGE_FACING_4PL[] =
    {
        // Southwest island: 5 ports; placing counterclockwise from northern end
        0x0601, FACING_NE,  0x0A01, FACING_NE,  0x0D03, FACING_E,
        0x0E04, FACING_NW,  0x0E07, FACING_NE,

        // Northeast island: 4 ports; placing counterclockwise from southeast end
        0x070E, FACING_W,   0x040E, FACING_SW,
        0x010D, FACING_W,   0x000B, FACING_SE
    };

    /**
     * Fog Island: Port types on the large southwest and northeast islands.
     * Since these won't be shuffled, they will line up with {@link #FOG_ISL_PORT_EDGE_FACING_4PL}.
     */
    private static final int FOG_ISL_PORT_TYPE_4PL[] =
    {
        // Southwest island: 5 ports; placing counterclockwise from northern end
        MISC_PORT, CLAY_PORT, MISC_PORT, WHEAT_PORT, SHEEP_PORT,

        // Northeast island: 4 ports; placing counterclockwise from southeast end
        WOOD_PORT, ORE_PORT, MISC_PORT, MISC_PORT
    };

    /**
     * Fog Island: Land and water hex coordinates for the fog island.
     * Hex types for the fog island are {@link #FOG_ISL_LANDHEX_TYPE_FOG}.
     */
    private static final int FOG_ISL_LANDHEX_COORD_FOG_4PL[] =
    {
        // Fog island: 12 hexes, from northwest to southeast;
        // the main part of the fog island is a diagonal line
        // of hexes from (1, 6) to (D, C).
        0x0104, 0x0305, 0x0506, 0x0707,
        0x0106, 0x0307, 0x0508, 0x0709, 0x090A, 0x0B0B, 0x0D0C,
        0x0B0D
    };

    /**
     * Fog Island: Dice numbers for hexes on the fog island.
     * No defined NumPath; as long as 6 and 8 aren't adjacent, all is OK.
     */
    private static final int FOG_ISL_DICENUM_FOG_4PL[] =
    {
        // 10 numbered hexes (excludes 2 water)
        3, 4, 5, 6, 8, 9, 10, 11, 11, 12
    };

    //
    // 6-player
    //

    /** Fog Island: Board size for 6 players: Max row 0x10, max col 0x14. */
    private static final int FOG_ISL_BOARDSIZE_6PL = 0x1014;

    /**
     * Fog Island: Land hex types for the northern main island.
     */
    private static final int FOG_ISL_LANDHEX_TYPE_MAIN_6PL[] =
    {
        // 24 hexes on main island
        CLAY_HEX, CLAY_HEX, CLAY_HEX, CLAY_HEX, CLAY_HEX,
        ORE_HEX, ORE_HEX, ORE_HEX, ORE_HEX, ORE_HEX,
        SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX,
        WHEAT_HEX, WHEAT_HEX, WHEAT_HEX, WHEAT_HEX, WHEAT_HEX,
        WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX,
        DESERT_HEX
    };

    /**
     * Fog Island: Land hex coordinates for the northern main island.
     */
    private static final int FOG_ISL_LANDHEX_COORD_MAIN_6PL[] =
    {
        // 24 hexes centered on rows 3-9, columns 3-0x11 (northmost row is 8 hexes across)
        0x0303, 0x0305, 0x0307, 0x0309, 0x030B, 0x030D, 0x030F, 0x0311,
        0x0504, 0x0506, 0x0508, 0x050A, 0x050C, 0x050E, 0x0510,
        0x0705, 0x0707, 0x0709, 0x070B, 0x070D, 0x070F,
        0x0908, 0x090A, 0x090C
    };

    /**
     * Fog Island: Dice numbers for hexes on the northern main island.
     * No defined NumPath; as long as 6 and 8 aren't adjacent, all is OK.
     */
    private static final int FOG_ISL_DICENUM_MAIN_6PL[] =
    {
        // 23 numbered hexes (excludes 1 desert)
        2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 6, 8, 8, 8, 9, 9, 10, 10, 11, 11, 11, 12, 12
    };

    /**
     * Fog Island: Port edges and facings on the northern main island.
     * Each port has 2 elements: Edge coordinate (0xRRCC), Port Facing.
     *<P>
     * Port Facing is the direction from the port edge, to the land hex touching it
     * which will have 2 nodes where a port settlement/city can be built.
     *<P>
     * Each port's type will be from {@link #FOG_ISL_PORT_TYPE_6PL}
     * which is shuffled.
     */
    private static final int FOG_ISL_PORT_EDGE_FACING_6PL[] =
    {
        // 9 ports; placing counterclockwise from northwest end
        0x0503, FACING_E,   0x0806, FACING_NE,  0x0A0A, FACING_NW,
        0x080D, FACING_NW,  0x0511, FACING_W,   0x0210, FACING_SE,
        0x020B, FACING_SW,  0x0206, FACING_SE,  0x0203, FACING_SW
    };

    /**
     * Fog Island: Port types on the northern main island.
     * These will be shuffled. Port locations are in {@link #FOG_ISL_PORT_EDGE_FACING_6PL}.
     */
    private static final int FOG_ISL_PORT_TYPE_6PL[] =
    {
        // 9 ports; 4 generic (3:1), 1 each of 2:1.
        MISC_PORT, MISC_PORT, MISC_PORT, MISC_PORT,
        CLAY_PORT, ORE_PORT, SHEEP_PORT, WHEAT_PORT, WOOD_PORT
    };

    /**
     * Fog Island: Land and water hex types for the fog island.
     */
    private static final int FOG_ISL_LANDHEX_TYPE_FOG_6PL[] =
    {
        // 25 hexes total (includes 12 water)
        WATER_HEX, WATER_HEX, WATER_HEX, WATER_HEX, WATER_HEX, WATER_HEX,
        WATER_HEX, WATER_HEX, WATER_HEX, WATER_HEX, WATER_HEX, WATER_HEX,
        CLAY_HEX, CLAY_HEX, ORE_HEX, ORE_HEX,
        SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, WHEAT_HEX, WHEAT_HEX,
        WOOD_HEX, WOOD_HEX, WOOD_HEX, GOLD_HEX
    };

    /**
     * Fog Island: Land and water hex coordinates for the fog island.
     * Hex types for the fog island are {@link #FOG_ISL_LANDHEX_TYPE_FOG_6PL}.
     */
    private static final int FOG_ISL_LANDHEX_COORD_FOG_6PL[] =
    {
        // Fog island: 25 hexes, in a wide arc from southwest to southeast.
        // The westernmost hex is centered at column 1, easternmost at 0x13.
        // The 2 thick rows along the south are row D and F.
        0x0701, 0x0713, 0x0902, 0x0912,
        0x0B01, 0x0B03, 0x0B05, 0x0B0F, 0x0B11, 0x0B13,
        0x0D02, 0x0D04, 0x0D06, 0x0D08, 0x0D0A, 0x0D0C, 0x0D0E, 0x0D10, 0x0D12,
        0x0F05, 0x0F07, 0x0F09, 0x0F0B, 0x0F0D, 0x0F0F
    };

    /**
     * Fog Island: Dice numbers for hexes on the fog island.
     * No defined NumPath; as long as 6 and 8 aren't adjacent, all is OK.
     */
    private static final int FOG_ISL_DICENUM_FOG_6PL[] =
    {
        // 13 numbered hexes total (excludes 12 water)
        2, 2, 3, 4, 5, 5, 6, 8, 9, 9, 10, 11, 12
    };

    /**
     * Fog Island: Land hex types for the gold corners (6-player only).
     */
    private static final int FOG_ISL_LANDHEX_TYPE_GC[] =
    {
        GOLD_HEX, GOLD_HEX
    };

    /**
     * Fog Island: Land hex coordinates for the gold corners (6-player only).
     */
    private static final int FOG_ISL_LANDHEX_COORD_GC[] =
    {
        0x0F03, 0x0F11
    };

    /**
     * Fog Island: Dice numbers for hexes on the gold corners (6-player only).
     */
    private static final int FOG_ISL_DICENUM_GC[] =
    {
        4, 10
    };


    ////////////////////////////////////////////
    //
    // The 4 Islands scenario Layout (SC_4ISL)
    //   Has 3-player, 4-player, 6-player versions
    //

    /** Four Islands: Pirate ship's starting hex coordinate for 3,4,6 players */
    private static final int FOUR_ISL_PIRATE_HEX[] =
    {
        0x0707, 0x070D, 0x0701
    };

    //
    // 3-player
    //

    /**
     * Four Islands: Land hex types for all 4 islands.
     */
    private static final int FOUR_ISL_LANDHEX_TYPE_3PL[] =
    {
        // 20 hexes total: 4 each of 5 resources
        CLAY_HEX, CLAY_HEX, CLAY_HEX, CLAY_HEX,
        ORE_HEX, ORE_HEX, ORE_HEX, ORE_HEX,
        SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX,
        WHEAT_HEX, WHEAT_HEX, WHEAT_HEX, WHEAT_HEX,
        WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX
    };

    /**
     * Four Islands: Land hex coordinates for all 4 islands.
     * The 4 island land areas are given by {@link #FOUR_ISL_LANDHEX_LANDAREA_RANGES_3PL}.
     */
    private static final int FOUR_ISL_LANDHEX_COORD_3PL[] =
    {
        // Northwest island: 4 hexes centered on rows 3,5, columns 2-5
        0x0303, 0x0305, 0x0502, 0x0504,

        // Southwest island: 6 hexes centered on rows 9,b,d, columns 2-6
        0x0902, 0x0904, 0x0906, 0x0B03, 0x0B05, 0x0D04,

        // Northeast island: 6 hexes centered on rows 1,3,5, columns 8-b
        0x0108, 0x010A, 0x0309, 0x030B, 0x0508, 0x050A,

        // Southeast island: 4 hexes centered on rows 9,b, columns 9-c
        0x090A, 0x090C, 0x0B09, 0x0B0B
    };

    /**
     * Four Islands: Dice numbers for all 4 islands.
     * No defined NumPath; as long as 6 and 8 aren't adjacent, all is OK.
     */
    private static final int FOUR_ISL_DICENUM_3PL[] =
    {
        // 20 hexes total, no deserts
        2, 3, 3, 4, 4, 5, 5, 5, 6, 6, 8, 8, 9, 9, 9, 10, 10, 11, 11, 12
    };

    /**
     * Four Islands: Island hex counts and land area numbers within {@link #FOUR_ISL_LANDHEX_COORD_3PL}.
     * Allows them to be defined, shuffled, and placed together.
     */
    private static final int FOUR_ISL_LANDHEX_LANDAREA_RANGES_3PL[] =
    {
            1, 4,  // landarea 1 is the northwest island with 4 hexes
            2, 6,  // landarea 2 SW
            3, 6,  // landarea 3 NE
            4, 4   // landarea 4 SE
    };

    /**
     * Four Islands: Port edges and facings on all 4 islands.
     * Each port has 2 elements: Edge coordinate (0xRRCC), Port Facing.
     *<P>
     * Port Facing is the direction from the port edge, to the land hex touching it
     * which will have 2 nodes where a port settlement/city can be built.
     *<P>
     * Each port's type will be from {@link #FOUR_ISL_PORT_TYPE_3PL}.
     */
    private static final int FOUR_ISL_PORT_EDGE_FACING_3PL[] =
    {
        0x0401, FACING_SE,  0x0405, FACING_NW, // Northwest island
        0x0802, FACING_SW,  0x0A01, FACING_NE,  0x0C05, FACING_NW,  // SW island
        0x020B, FACING_SW,  0x0609, FACING_NE, // NE island
        0x0A08, FACING_SE,  0x0A0C, FACING_NW  // SE island
    };

    /**
     * Four Islands: Port types on all 4 islands.  OK to shuffle.
     */
    private static final int FOUR_ISL_PORT_TYPE_3PL[] =
    {
        MISC_PORT, ORE_PORT,
        WOOD_PORT, MISC_PORT, SHEEP_PORT,
        MISC_PORT, CLAY_PORT,
        MISC_PORT, WHEAT_PORT
    };

    //
    // 4-player
    //

    /** Four Islands: Board size for 4 players: Max row 0x10, max col 0x0e. */
    private static final int FOUR_ISL_BOARDSIZE_4PL = 0x100e;

    /**
     * Four Islands: Land hex types for all 4 islands.
     */
    private static final int FOUR_ISL_LANDHEX_TYPE_4PL[] =
    {
        // 23 hexes total: 4 or 5 each of 5 resources
        CLAY_HEX, CLAY_HEX, CLAY_HEX, CLAY_HEX,
        ORE_HEX, ORE_HEX, ORE_HEX, ORE_HEX,
        SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX,
        WHEAT_HEX, WHEAT_HEX, WHEAT_HEX, WHEAT_HEX, WHEAT_HEX,
        WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX
    };

    /**
     * Four Islands: Land hex coordinates for all 4 islands.
     */
    private static final int FOUR_ISL_LANDHEX_COORD_4PL[] =
    {
        // Northwest island: 4 hexes centered on rows 1,3,5, columns 2-4
        0x0104, 0x0303, 0x0502, 0x0504,

        // Southwest island: 7 hexes centered on rows 9,b,d, columns 2-7
        0x0902, 0x0904, 0x0B03, 0x0B05, 0x0B07, 0x0D04, 0x0D06,

        // Northeast island: 8 hexes centered on rows 1,3,5, columns 7-b, outlier at (7,7)
        0x0108, 0x010A, 0x0307, 0x0309, 0x030B, 0x0508, 0x050A, 0x0707,

        // Southeast island: 4 hexes centered on rows 9,b,d, columns a-c
        0x090A, 0x090C, 0x0B0B, 0x0D0A
    };

    /**
     * Four Islands: Dice numbers for hexes on all 4 islands.
     * No defined NumPath; as long as 6 and 8 aren't adjacent, all is OK.
     */
    private static final int FOUR_ISL_DICENUM_4PL[] =
    {
        // 23 hexes total, no deserts
        2, 3, 3, 4, 4, 4, 5, 5, 5, 6, 6, 8, 8, 9, 9, 9, 10, 10, 10, 11, 11, 11, 12
    };

    /**
     * Four Islands: Island hex counts and land area numbers within {@link #FOUR_ISL_LANDHEX_COORD_4PL}.
     * Allows them to be defined, shuffled, and placed together.
     */
    private static final int FOUR_ISL_LANDHEX_LANDAREA_RANGES_4PL[] =
    {
            1, 4,  // landarea 1 is the northwest island with 4 hexes
            2, 7,  // landarea 2 SW
            3, 8,  // landarea 3 NE
            4, 4   // landarea 4 SE
    };

    /**
     * Four Islands: Port edges and facings on all 4 islands.
     * Each port has 2 elements: Edge coordinate (0xRRCC), Port Facing.
     *<P>
     * Each port's type will be from {@link #FOUR_ISL_PORT_TYPE_4PL}.
     */
    private static final int FOUR_ISL_PORT_EDGE_FACING_4PL[] =
    {
        0x0302, FACING_E,   0x0602, FACING_NW,
        0x0A01, FACING_NE,  0x0A05, FACING_SW,  0x0D03, FACING_E,
        0x0606, FACING_SE,  0x040B, FACING_NW,
        0x080B, FACING_SE,  0x0A0C, FACING_NW
    };

    /**
     * Four Islands: Port types on all 4 islands.  OK to shuffle.
     */
    private static final int FOUR_ISL_PORT_TYPE_4PL[] =
    {
        WHEAT_PORT, MISC_PORT,
        CLAY_PORT, MISC_PORT, SHEEP_PORT,
        MISC_PORT, WOOD_PORT,
        ORE_PORT, MISC_PORT
    };

    //
    // 6-player
    //

    /** Four Islands: Board size for 6 players: Max row 0x10, max col 0x14. */
    private static final int FOUR_ISL_BOARDSIZE_6PL = 0x1014;

    /**
     * Six Islands: Land hex types for all 6 islands.
     */
    private static final int FOUR_ISL_LANDHEX_TYPE_6PL[] =
    {
        // 32 hexes total: 6 or 7 each of 5 resources
        CLAY_HEX, CLAY_HEX, CLAY_HEX, CLAY_HEX, CLAY_HEX, CLAY_HEX,
        ORE_HEX, ORE_HEX, ORE_HEX, ORE_HEX, ORE_HEX, ORE_HEX,
        SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX,
        WHEAT_HEX, WHEAT_HEX, WHEAT_HEX, WHEAT_HEX, WHEAT_HEX, WHEAT_HEX,
        WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX
    };

    /**
     * Six Islands: Land hex coordinates for all 6 islands.
     */
    private static final int FOUR_ISL_LANDHEX_COORD_6PL[] =
    {
        // All 3 Northern islands are centered on rows 1,3,5.

        // Northwest island: 6 hexes centered on columns 2-6
        0x0104, 0x0106, 0x0303, 0x0305, 0x0502, 0x0504,

        // Center North island: 5 hexes  on columns 8-b
        0x010A, 0x0309, 0x030B, 0x0508, 0x050A,

        // Northeast island: 5 hexes  on columns e-0x11
        0x010E, 0x0110, 0x030F, 0x0311, 0x0510,

        // All 3 Southern islands are centered on rows 9,b,d.

        // Southwest island: 5 hexes centered on columns 3-6
        0x0904, 0x0B03, 0x0B05, 0x0D04, 0x0D06,

        // Center South island: 5 hexes on columns 9-c
        0x090A, 0x090C, 0x0B09, 0x0B0B, 0x0D0A,

        // Southeast island: 6 hexes on columns e-0x12
        0x0910, 0x0912, 0x0B0F, 0x0B11, 0x0D0E, 0x0D10
    };

    /**
     * Six Islands: Dice numbers for hexes on all 6 islands.
     * No defined NumPath; as long as 6 and 8 aren't adjacent, all is OK.
     */
    private static final int FOUR_ISL_DICENUM_6PL[] =
    {
        // 32 hexes total, no deserts
        2, 2, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6,
        8, 8, 8, 9, 9, 9, 9, 10, 10, 10, 10, 11, 11, 12, 12
    };

    /**
     * Six Islands: Island hex counts and land area numbers within {@link #FOUR_ISL_LANDHEX_COORD_6PL}.
     * Allows them to be defined, shuffled, and placed together.
     */
    private static final int FOUR_ISL_LANDHEX_LANDAREA_RANGES_6PL[] =
    {
            1, 6,  // landarea 1 is the northwest island with 6 hexes
            2, 5,  // landarea 2 is center north
            3, 5,  // landarea 3 NE
            4, 5,  // SW
            5, 5,  // S
            6, 6   // SE
    };

    /**
     * Six Islands: Port edges and facings on all 6 islands.
     * Each port has 2 elements: Edge coordinate (0xRRCC), Port Facing.
     *<P>
     * Each port's type will be from {@link #FOUR_ISL_PORT_TYPE_6PL}.
     */
    private static final int FOUR_ISL_PORT_EDGE_FACING_6PL[] =
    {
        // 1 row here per island, same order as hexes
        0x0005, FACING_SE,  0x0405, FACING_NW,  0x0603, FACING_NE,
        0x000A, FACING_SW,
        0x0010, FACING_SW,  0x040E, FACING_NE,
        0x0B02, FACING_E,   0x0C06, FACING_SW,
        0x0C08, FACING_NE,
        0x0B12, FACING_W,   0x0E0D, FACING_NE
    };

    /**
     * Six Islands: Port types on all 6 islands.  OK to shuffle.
     */
    private static final int FOUR_ISL_PORT_TYPE_6PL[] =
    {
        // 5 3:1, 6 2:1 ports (extra is sheep)
        CLAY_PORT, ORE_PORT, SHEEP_PORT, SHEEP_PORT, WHEAT_PORT, WOOD_PORT,
        MISC_PORT, MISC_PORT, MISC_PORT, MISC_PORT, MISC_PORT
    };


    ////////////////////////////////////////////
    //
    // Pirate Island scenario Layout (_SC_PIRI)
    //   Has 4-player, 6-player versions;
    //   each array here uses index [0] for 4-player, [1] for 6-player.
    //   LA#1 is starting land area.  Pirate Islands have no land area,
    //   the player can place 1 lone settlement (added layout part "LS")
    //   at a coordinate within PIR_ISL_INIT_PIECES.
    //

    /**
     * Pirate Islands: Board size:
     * 4 players max row 0x10, max col 0x12.
     * 6 players max row 0x10, max col 0x16.
     */
    private static final int PIR_ISL_BOARDSIZE[] = { 0x1012, 0x1016 };

    /**
     * Pirate Islands: Starting pirate sea hex coordinate for 4, 6 players.
     */
    private static final int PIR_ISL_PIRATE_HEX[] = { 0x0D0A, 0x0D0A };

    /**
     * Pirate Islands: Land hex types for the large eastern starting island.
     * Each row from west to east.  These won't be shuffled.
     */
    private static final int PIR_ISL_LANDHEX_TYPE_MAIN[][] =
    {{
        // 4-player: 17 hexes; 7 rows, each row 2 or 3 hexes across
        WHEAT_HEX, CLAY_HEX, ORE_HEX, WOOD_HEX,
        WOOD_HEX, SHEEP_HEX, WOOD_HEX,
        WHEAT_HEX, CLAY_HEX, SHEEP_HEX,
        SHEEP_HEX, WOOD_HEX, SHEEP_HEX,
        ORE_HEX, WOOD_HEX, WHEAT_HEX, CLAY_HEX
    }, {
        // 6-player: 24 hexes; 7 rows, each row 3 or 4 hexes across
        SHEEP_HEX, WHEAT_HEX, CLAY_HEX,
        ORE_HEX, CLAY_HEX, WOOD_HEX,
        WOOD_HEX, WOOD_HEX, SHEEP_HEX, WOOD_HEX,
        WHEAT_HEX, WHEAT_HEX, ORE_HEX, SHEEP_HEX,
        SHEEP_HEX, SHEEP_HEX, CLAY_HEX, SHEEP_HEX,
        WHEAT_HEX, ORE_HEX, CLAY_HEX,
        WOOD_HEX, WHEAT_HEX, WOOD_HEX
    }};

    /**
     * Pirate Islands: Land hex coordinates for the large eastern starting island.
     * Indexes line up with {@link #PIR_ISL_LANDHEX_TYPE_MAIN}, because they won't be shuffled.
     */
    private static final int PIR_ISL_LANDHEX_COORD_MAIN[][] =
    {{
        // 4-player: 17 hexes; 7 rows, centered on columns b - 0x10
        0x010C, 0x010E, 0x030D, 0x030F,
        0x050C, 0x050E, 0x0510,
        0x070B, 0x070D, 0x070F,
        0x090C, 0x090E, 0x0910,
        0x0B0D, 0x0B0F, 0x0D0C, 0x0D0E
    }, {
        // 6-player: 24 hexes; 7 rows, centered on columns d - 0x14
        0x010E, 0x0110, 0x0112,
        0x030F, 0x0311, 0x0313,
        0x050E, 0x0510, 0x0512, 0x0514,
        0x070D, 0x070F, 0x0711, 0x0713,
        0x090E, 0x0910, 0x0912, 0x0914,
        0x0B0F, 0x0B11, 0x0B13,
        0x0D0E, 0x0D10, 0x0D12
    }};

    /**
     * Pirate Islands: Dice numbers for hexes on the large eastern starting island.
     * NumPath is {@link #PIR_ISL_LANDHEX_COORD_MAIN}.
     */
    private static final int PIR_ISL_DICENUM_MAIN[][] =
    {{
        4, 5, 9, 10, 3, 8, 5, 6, 9, 12, 11, 8, 9, 5, 2, 10, 4
    }, {
        5, 4, 9, 3, 10, 11, 12, 6, 4, 10,
        6, 4, 5, 8,  // <-- center row
        2, 10, 3, 5, 11, 9, 8, 9, 5, 4
    }};

    /**
     * Pirate Islands: Port edges and facings on the large eastern starting island.
     * Clockwise, starting at northwest corner of island.
     * Each port has 2 elements: Edge coordinate (0xRRCC), Port Facing.
     *<P>
     * Port Facing is the direction from the port edge, to the land hex touching it
     * which will have 2 nodes where a port settlement/city can be built.
     *<P>
     * Each port's type will be {@link #PIR_ISL_PORT_TYPE}[i].
     */
    private static final int PIR_ISL_PORT_EDGE_FACING[][] =
    {{
        // 4 players
        0x000B, FACING_SE,  0x000D, FACING_SE,  0x010F, FACING_W,
        0x0310, FACING_W,   0x0710, FACING_W,   0x0A10, FACING_NW,
        0x0C0F, FACING_NW,  0x0E0C, FACING_NW
    }, {
        // 6 players
        0x000F, FACING_SE,  0x0011, FACING_SE,  0x0113, FACING_W,
        0x0314, FACING_W,   0x0714, FACING_W,   0x0B14, FACING_W,
        0x0D13, FACING_W,   0x0E11, FACING_NE,  0x0E0F, FACING_NE
    }};

    /**
     * Pirate Islands: Port types on the large eastern starting island.  Will be shuffled.
     */
    private static final int PIR_ISL_PORT_TYPE[][] =
    {{
        // 4 players:
        MISC_PORT, MISC_PORT, MISC_PORT,
        CLAY_PORT, ORE_PORT, SHEEP_PORT, WHEAT_PORT, WOOD_PORT
    }, {
        // 6 players:
        MISC_PORT, MISC_PORT, MISC_PORT, MISC_PORT,
        CLAY_PORT, ORE_PORT, SHEEP_PORT, WHEAT_PORT, WOOD_PORT
    }};

    /**
     * Pirate Islands: Hex land types on the several pirate islands.
     * Only the first several have dice numbers.
     */
    private static final int PIR_ISL_LANDHEX_TYPE_PIRI[][] =
    {{
        // 4 players, see PIR_ISL_LANDHEX_COORD_PIRI for layout details
        ORE_HEX, GOLD_HEX, WHEAT_HEX, ORE_HEX, GOLD_HEX, WHEAT_HEX, ORE_HEX,
        CLAY_HEX, CLAY_HEX, DESERT_HEX, DESERT_HEX, DESERT_HEX, SHEEP_HEX
    }, {
        // 6 players
        GOLD_HEX, ORE_HEX, GOLD_HEX, ORE_HEX, GOLD_HEX, ORE_HEX, GOLD_HEX, ORE_HEX,
        DESERT_HEX, DESERT_HEX, DESERT_HEX, DESERT_HEX, DESERT_HEX
    }};

    /**
     * Pirate Islands: Land hex coordinates for the several pirate islands.
     * Hex types for the pirate island are {@link #PIR_ISL_LANDHEX_TYPE_PIRI}.
     * Only the first several have dice numbers ({@link #PIR_ISL_DICENUM_PIRI}).
     */
    private static final int PIR_ISL_LANDHEX_COORD_PIRI[][] =
    {{
        // 4 players: With dice numbers: Northwest and southwest islands, center-west island
        0x0106, 0x0104, 0x0502, 0x0D06, 0x0D04, 0x0902, 0x0705,
        //            Without numbers: Northwest, southwest, north-center, south-center
        0x0303, 0x0B03, 0x0309, 0x0508, 0x0908, 0x0B09
    }, {
        // 6 players: With dice numbers: Scattered from north to south
        0x0104, 0x0108, 0x0502, 0x0506, 0x0902, 0x0906, 0x0D04, 0x0D08,
        //            Without numbers: Scattered north to south, center or west
        0x030B, 0x0504, 0x0709, 0x0904, 0x0B0B
    }};

    /**
     * Pirate Islands: Dice numbers for the first few land hexes along {@link #PIR_ISL_LANDHEX_COORD_PIRI}
     */
    private static final int PIR_ISL_DICENUM_PIRI[][] =
    {{
        // 4 players
        6, 11, 4, 6, 3, 10, 8
    }, {
        // 6 players
        3, 6, 11, 8, 11, 6, 3, 8
    }};

    /**
     * Pirate Islands: The hex-coordinate path for the Pirate Fleet; SOCBoardLarge additional part <tt>"PP"</tt>.
     * First element is the pirate starting position.  {@link #piratePathIndex} is already 0.
     */
    private static final int PIR_ISL_PPATH[][] =
    {{
        // 4 players
        0x0D0A, 0x0D08, 0x0B07, 0x0906, 0x0707, 0x0506, 0x0307,
        0x0108, 0x010A, 0x030B, 0x050A, 0x0709, 0x090A, 0x0B0B
    }, {
        // 6 players
        0x0D0A, 0x0B09, 0x0908, 0x0707, 0x0508, 0x0309, 0x010A,
        0x010C, 0x030D, 0x050C, 0x070B, 0x090C, 0x0B0D, 0x0D0C
    }};

    /**
     * Pirate Islands: Sea edges legal/valid for each player to build ships directly to their Fortress.
     * Each player has 1 array, in same player order as {@link #PIR_ISL_INIT_PIECES}
     * (given out to non-vacant players, not strictly matching player number).
     *<P>
     * Note {@link SOCPlayer#doesTradeRouteContinuePastNode(SOCBoard, boolean, int, int, int)}
     * assumes there is just 1 legal sea edge, not 2, next to each player's free initial settlement,
     * so it won't need to check if the ship route would branch in 2 directions there.
     *<P>
     * See {@link #getLegalSeaEdges(SOCGame, int)} for how this is rearranged to be sent to
     * active player clients as part of a {@code SOCPotentialSettlements} message,
     * and the format of each player's array.
     */
    private static final int PIR_ISL_SEA_EDGES[][][] =
    {{
        // 4 players
        { 0xC07, -0xC0B, 0xD07, -0xD0B, 0xE04, -0xE0A },
        { 0x207, -0x20B, 0x107, -0x10B, 0x004, -0x00A },
        { 0x803, -0x80A, 0x903, 0x905, 0xA03, 0xA04 },
        { 0x603, -0x60A, 0x503, 0x505, 0x403, 0x404 }
    }, {
        // 6 players
        { 0xC04, -0xC0D }, { 0x204, -0x20D },
        { 0x803, -0x80C }, { 0x603, -0x60C },
        { 0x003, -0x00C }, { 0xE03, -0xE0C }
    }};

    /**
     * Pirate Islands: Initial piece coordinates for each player,
     * in same player order as {@link #PIR_ISL_SEA_EDGES}
     * (given out to non-vacant players, not strictly matching player number).
     * Each player has 4 elements, starting at index <tt>4 * playerNumber</tt>:
     * Initial settlement node, initial ship edge, pirate fortress node,
     * and the node on the pirate island where they are allowed to build
     * a settlement on the way to the fortress (layout part "LS").
     */
    private static final int PIR_ISL_INIT_PIECES[][] =
    {{
        // 4 players
        0x0C0C, 0x0C0B, 0x0E04, 0x0E07,
        0x020C, 0x020B, 0x0004, 0x0007,
        0x080B, 0x080A, 0x0A03, 0x0805,
        0x060B, 0x060A, 0x0403, 0x0605
    }, {
        // 6 players
        0x0C0E, 0x0C0D, 0x0C04, 0x0C08,
        0x020E, 0x020D, 0x0204, 0x0208,
        0x080D, 0x080C, 0x0803, 0x0807,
        0x060D, 0x060C, 0x0603, 0x0607,
        0x000D, 0x000C, 0x0003, 0x0009,
        0x0E0D, 0x0E0C, 0x0E03, 0x0E09
    }};


    ////////////////////////////////////////////
    //
    // Through The Desert scenario Layout (SC_TTD)
    //   Has 3-player, 4-player, 6-player versions;
    //   each array here uses index [0] for 3-player, [1] for 4-player, [2] for 6-player.
    //

    /**
     * Through The Desert: Board size for 3, 4, 6 players: Each is 0xrrcc (max row, max col).
     */
    private static final int TTDESERT_BOARDSIZE[] = { 0x1010, 0x1012, 0x1016 };

    /**
     * Through The Desert: Starting pirate sea hex coordinate for 3, 4, 6 players.
     */
    private static final int TTDESERT_PIRATE_HEX[] = { 0x070D, 0x070F, 0x0D10 };

    /**
     * Through The Desert: Land hex types for the main island (land areas 1, 2). These will be shuffled.
     * The main island also includes 3 or 5 deserts that aren't shuffled, so they aren't in this array.
     *<P>
     * The main island has just 1 <tt>GOLD_HEX</tt>, which will always be placed in landarea 2,
     * the small strip of land past the desert.  This is handled by
     * {@link #makeNewBoard_placeHexes_arrangeGolds(int[], int[], String)}.
     *
     * @see #TTDESERT_LANDHEX_COORD_MAIN
     */
    private static final int TTDESERT_LANDHEX_TYPE_MAIN[][] =
    {{
        // 3-player: 17 (14+3) hexes
        CLAY_HEX, CLAY_HEX, CLAY_HEX,
        ORE_HEX, ORE_HEX,
        SHEEP_HEX, SHEEP_HEX, SHEEP_HEX,
        WHEAT_HEX, WHEAT_HEX, WHEAT_HEX,
        WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX,
        GOLD_HEX
    }, {
        // 4-player: 20 (17+3) hexes
        CLAY_HEX, CLAY_HEX, CLAY_HEX, CLAY_HEX,
        ORE_HEX, ORE_HEX, ORE_HEX,
        SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX,
        WHEAT_HEX, WHEAT_HEX, WHEAT_HEX,
        WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX,
        GOLD_HEX
    }, {
        // 6-player: 30 (21+9) hexes
        CLAY_HEX, CLAY_HEX, CLAY_HEX, CLAY_HEX, CLAY_HEX, CLAY_HEX, CLAY_HEX,
        ORE_HEX, ORE_HEX, ORE_HEX, ORE_HEX, ORE_HEX, ORE_HEX,
        SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX,
        WHEAT_HEX, WHEAT_HEX, WHEAT_HEX, WHEAT_HEX, WHEAT_HEX,
        WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX,
        GOLD_HEX
    }};

    /**
     * Through The Desert: Land Area 1, 2: Land hex coordinates for the main island,
     * excluding its desert strip but including the small fertile area on the desert's far side.
     * Landarea 1 is most of the island.
     * Landarea 2 is the small fertile strip.
     * @see #TTDESERT_LANDHEX_COORD_DESERT
     * @see #TTDESERT_LANDHEX_COORD_SMALL
     */
    private static final int TTDESERT_LANDHEX_COORD_MAIN[][] =
    {{
        // 3-player: 14 hexes; 6 rows, hex centers on columns 2-8
        0x0307, 0x0506, 0x0508, 0x0705, 0x0707,
        0x0902, 0x0904, 0x0906, 0x0908,
        0x0B03, 0x0B05, 0x0B07, 0x0D04, 0x0D06,
        // Past the desert:
        0x0104, 0x0303, 0x0502
    }, {
        // 4-player: 17 hexes; 7 rows, columns 2-A
        0x0108, 0x0307, 0x0309,
        0x0506, 0x0508, 0x050A,
        0x0705, 0x0707, 0x0709,
        0x0902, 0x0904, 0x0906, 0x0908,
        0x0B03, 0x0B05, 0x0B07, 0x0D06,
        // Past the desert:
        0x0104, 0x0303, 0x0502
    }, {
        // 6-player: 21 hexes; 5 rows, columns 2-F
        0x0508, 0x050A, 0x050C, 0x050E,
        0x0703, 0x0705, 0x0707, 0x0709, 0x070B, 0x070D, 0x070F,
        0x0902, 0x0904, 0x0906, 0x0908, 0x090A, 0x090C, 0x090E,
        0x0B03, 0x0B05, 0x0D04,
        // Past the desert:
        0x0305, 0x0303, 0x0104, 0x0106, 0x0108, /* 1-hex gap, */ 0x010C, 0x010E, 0x0110, 0x0112
    }};

    /**
     * Through The Desert: Land Areas 1, 2:
     * Hex counts and Land area numbers for the main island, excluding its desert,
     * within {@link #TTDESERT_LANDHEX_COORD_MAIN}.
     * Allows us to shuffle them all together within {@link #TTDESERT_LANDHEX_TYPE_MAIN}.
     * Dice numbers are {@link #TTDESERT_DICENUM_MAIN}.
     * Landarea 1 is most of the island.
     * Landarea 2 is the small fertile strip on the other side of the desert.
     */
    private static final int TTDESERT_LANDHEX_RANGES_MAIN[][] =
    {{
        // 3 players
        1, TTDESERT_LANDHEX_COORD_MAIN[0].length - 3,
        2, 3
    }, {
        // 4 players
        1, TTDESERT_LANDHEX_COORD_MAIN[1].length - 3,
        2, 3
    }, {
        // 6 players
        1, TTDESERT_LANDHEX_COORD_MAIN[2].length - 9,
        2, 9
    }};

    /**
     * Through The Desert: Land hex coordinates for the strip of desert hexes on the main island.
     * @see #TTDESERT_LANDHEX_COORD_MAIN
     */
    private static final int TTDESERT_LANDHEX_COORD_DESERT[][] =
    {{ 0x0106, 0x0305, 0x0504 },  // 3-player
     { 0x0106, 0x0305, 0x0504 },  // 4-player same as 3-player
     { 0x0307, 0x0309, 0x030B, 0x030D, 0x030F }  // 6-player
    };

    /**
     * Through The Desert: Dice numbers for hexes on the main island,
     * including the strip of land past the desert.  Will be shuffled.
     * NumPath is {@link #TTDESERT_LANDHEX_COORD_MAIN}.
     * @see #TTDESERT_DICENUM_SMALL
     */
    private static final int TTDESERT_DICENUM_MAIN[][] =
    {{
        // 3 players
        2, 3, 3, 4, 4, 4, 5, 6, 6, 6, 8, 8, 9, 9, 10, 10, 11
    }, {
        // 4 players
        3, 3, 4, 4, 5, 5, 6, 6, 8, 8, 8, 9, 9, 10, 10, 10, 11, 11, 11, 12
    }, {
        // 6 players
        2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 5, 5,
        6, 6, 6, 6, 8, 8, 9, 9, 9, 10, 10, 10,
        11, 11, 11, 12, 12, 12
    }};

    /**
     * Through The Desert: Port edges and facings on the main island.
     * Clockwise, starting at northwest corner of island.
     * Each port has 2 elements: Edge coordinate (0xRRCC), Port Facing.
     *<P>
     * Port Facing is the direction from the port edge, to the land hex touching it
     * which will have 2 nodes where a port settlement/city can be built.
     *<P>
     * Each port's type will be {@link #TTDESERT_PORT_TYPE}[i][j].
     */
    private static final int TTDESERT_PORT_EDGE_FACING[][] =
    {{
        // 3 players
        0x0207, FACING_SW,  0x0808, FACING_SW,  0x0A08, FACING_NW,
        0x0D07, FACING_W,   0x0E04, FACING_NW,  0x0D03, FACING_E,
        0x0A01, FACING_NE,  0x0803, FACING_SE
    }, {
        // 4 players
        0x0109, FACING_W,   0x040A, FACING_SW,  0x060A, FACING_NW,
        0x0A08, FACING_NW,  0x0D07, FACING_W,   0x0E05, FACING_NE,
        0x0C03, FACING_NW,  0x0B02, FACING_E,   0x0704, FACING_E
    }, {
        // 6 players
        0x0B02, FACING_E,   0x0801, FACING_SE,  0x0602, FACING_SE,
        0x0606, FACING_SE,  0x050F, FACING_W,   0x080F, FACING_NW,
        0x0A0D, FACING_NE,  0x0A09, FACING_NE,  0x0A06, FACING_NW,
        0x0C05, FACING_NW,  0x0E03, FACING_NE
    }};

    /**
     * Through The Desert: Port types on the main island.  Will be shuffled.
     * The small islands have no ports.
     * Each port's edge and facing will be {@link #TTDESERT_PORT_EDGE_FACING}[i][j].
     */
    private static final int TTDESERT_PORT_TYPE[][] =
    {{
        // 3 players:
        MISC_PORT, MISC_PORT, MISC_PORT,
        CLAY_PORT, ORE_PORT, SHEEP_PORT, WHEAT_PORT, WOOD_PORT
    }, {
        // 4 players:
        MISC_PORT, MISC_PORT, MISC_PORT, MISC_PORT,
        CLAY_PORT, ORE_PORT, SHEEP_PORT, WHEAT_PORT, WOOD_PORT
    }, {
        // 6 players:
        MISC_PORT, MISC_PORT, MISC_PORT, MISC_PORT, MISC_PORT,
        CLAY_PORT, ORE_PORT, SHEEP_PORT, SHEEP_PORT, WHEAT_PORT, WOOD_PORT
    }};

    /**
     * Through The Desert: Hex land types on the several small islands.
     * Coordinates for these islands are {@link #TTDESERT_LANDHEX_COORD_SMALL}.
     */
    private static final int TTDESERT_LANDHEX_TYPE_SMALL[][] =
    {{
        // 3 players
        ORE_HEX, ORE_HEX, SHEEP_HEX, WHEAT_HEX, GOLD_HEX
    }, {
        // 4 players
        CLAY_HEX, ORE_HEX, ORE_HEX, SHEEP_HEX,
        WHEAT_HEX, WHEAT_HEX, GOLD_HEX
    }, {
        // 6 players
        ORE_HEX, SHEEP_HEX, SHEEP_HEX, WHEAT_HEX, WHEAT_HEX,
        WOOD_HEX, GOLD_HEX, GOLD_HEX
    }};

    /**
     * Through The Desert: Land Areas 3 to n:
     * Hex counts and Land area numbers for each of the small "foreign" islands, one per island,
     * within {@link #TTDESERT_LANDHEX_COORD_SMALL}.
     * Allows us to shuffle them all together within {@link #TTDESERT_LANDHEX_TYPE_SMALL}.
     * Dice numbers are {@link #TTDESERT_DICENUM_SMALL}.
     * Total land area count for this layout varies, will be 2 + (<tt>TTDESERT_LANDHEX_RANGES_SMALL[i].length</tt> / 2).
     */
    private static final int TTDESERT_LANDHEX_RANGES_SMALL[][] =
    {{
        // 3 players
        3, 2,  // landarea 3 is an island with 2 hexes (see TTDESERT_LANDHEX_COORD_SMALL)
        4, 1,  // landarea 4
        5, 2   // landarea 5
    }, {
        // 4 players
        3, 3,
        4, 2,
        5, 2
    }, {
        // 6 players
        3, 2,
        4, 1,
        5, 4,
        6, 1
    }};

    /**
     * Through The Desert: Land Areas 3 to n:
     * Land hex coordinates for all of the small "foreign" islands, one LA per island.
     * Hex types for these islands are {@link #TTDESERT_LANDHEX_TYPE_SMALL}.
     * Dice numbers are {@link #TTDESERT_DICENUM_SMALL}.
     * Land area numbers are split up via {@link #TTDESERT_LANDHEX_RANGES_SMALL}.
     */
    private static final int TTDESERT_LANDHEX_COORD_SMALL[][] =
    {{
        // 3 players
        0x010A, 0x030B,  // landarea 3 is an island with 2 hexes (see TTDESERT_LANDHEX_RANGES_SMALL)
        0x070B,          // landarea 4
        0x0B0B, 0x0D0A   // landarea 5
    }, {
        // 4 players
        0x010C, 0x030D, 0x050E,
        0x090C, 0x090E,
        0x0D0A, 0x0D0C
    }, {
        // 6 players
        0x0D08, 0x0D0A,
        0x0D0E,
        0x0D12, 0x0B11, 0x0B13, 0x0914,
        0x0514
    }};

    /**
     * Through The Desert: Dice numbers for all the small islands ({@link #TTDESERT_LANDHEX_COORD_SMALL}).
     * @see #TTDESERT_DICENUM_MAIN
     */
    private static final int TTDESERT_DICENUM_SMALL[][] =
    {{
        // 3 players
        5, 5, 8, 9, 11
    }, {
        // 4 players
        2, 3, 4, 5, 6, 9, 12
    }, {
        // 6 players
        2, 3, 4, 8, 8, 9, 10, 11
    }};


    ////////////////////////////////////////////
    //
    // Forgotten Tribe scenario Layout (_SC_FTRI)
    //   Has 4-player, 6-player versions;
    //   each array here uses index [0] for 4-player, [1] for 6-player.
    //   LA#1 is the larger main island.
    //   LA#0 is the surrounding islands; players can't settle there.
    //   No ports on the main island, only surrounding islands.
    //

    /**
     * Forgotten Tribe: Board size:
     * 4 players max row 0x0E, max col 0x11.
     * 6 players max row 0x0E, max col 0x15.
     */
    private static final int FOR_TRI_BOARDSIZE[] = { 0x0E11, 0x0E15 };

    /**
     * Forgotten Tribe: Starting pirate sea hex coordinate for 4, 6 players.
     */
    private static final int FOR_TRI_PIRATE_HEX[] = { 0x0108, 0x010E };

    /**
     * Forgotten Tribe: Land hex types for the main island. Shuffled.
     */
    private static final int FOR_TRI_LANDHEX_TYPE_MAIN[][] =
    {{
        // 4-player: 18 hexes
        CLAY_HEX, CLAY_HEX, CLAY_HEX, ORE_HEX, ORE_HEX, ORE_HEX,
        SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, WHEAT_HEX, WHEAT_HEX,
        WHEAT_HEX, WHEAT_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX
    }, {
        // 6-player: 29 hexes
        CLAY_HEX, CLAY_HEX, CLAY_HEX, CLAY_HEX, CLAY_HEX, CLAY_HEX,
        ORE_HEX, ORE_HEX, ORE_HEX, ORE_HEX, ORE_HEX,
        SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX, SHEEP_HEX,
        WHEAT_HEX, WHEAT_HEX, WHEAT_HEX, WHEAT_HEX, WHEAT_HEX, WHEAT_HEX,
        WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX, WOOD_HEX
    }};

    /**
     * Forgotten Tribe: Land hex coordinates for the main island.
     */
    private static final int FOR_TRI_LANDHEX_COORD_MAIN[][] =
    {{
        // 4-player: 18 hexes; 3 rows, centered on columns 2 - d
        0x0502, 0x0504, 0x0506, 0x0508, 0x050A, 0x050C,
        0x0703, 0x0705, 0x0707, 0x0709, 0x070B, 0x070D,
        0x0902, 0x0904, 0x0906, 0x0908, 0x090A, 0x090C
    }, {
        // 6-player: 29 hexes; 3 rows, centered on columns 2 - 0x14
        0x0502, 0x0504, 0x0506, 0x0508, 0x050A, 0x050C, 0x050E, 0x0510, 0x0512, 0x0514,
        0x0703, 0x0705, 0x0707, 0x0709, 0x070B, 0x070D, 0x070F, 0x0711, 0x0713,
        0x0902, 0x0904, 0x0906, 0x0908, 0x090A, 0x090C, 0x090E, 0x0910, 0x0912, 0x0914
    }};

    /**
     * Forgotten Tribe: Dice numbers for hexes on the main island. Shuffled.
     */
    private static final int FOR_TRI_DICENUM_MAIN[][] =
    {{
        2, 3, 3, 4, 4, 5, 5, 6, 6,
        8, 8, 9, 9, 10, 10, 11, 11, 12
    }, {
        2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6,
        8, 8, 8, 9, 9, 9, 10, 10, 10, 11, 11, 11, 12
    }};

    /**
     * Forgotten Tribe: Port edges and facings. There are no ports on the main island, only the surrounding islands.
     *<P>
     * Clockwise, starting at northwest corner of board.
     * Each port has 2 elements: Edge coordinate (0xRRCC), Port Facing.
     *<P>
     * Port Facing is the direction from the port edge, to the land hex touching it
     * which will have 2 nodes where a port settlement/city can be built.
     *<P>
     * Port types ({@link #FOR_TRI_PORT_TYPE}) are shuffled.
     */
    private static final int FOR_TRI_PORT_EDGE_FACING[][] =
    {{
        // 4 players
        0x0003, FACING_SE,  0x0009, FACING_SE,  0x0410, FACING_SW,
        0x0A10, FACING_NW,  0x0E0A, FACING_NW,  0x0E03, FACING_NE
    }, {
        // 6 players
        0x0006, FACING_SW,  0x0009, FACING_SE,  0x000F, FACING_SE, 0x0012, FACING_SW,
        0x0E0F, FACING_NE,  0x0E0C, FACING_NW,  0x0E05, FACING_NE, 0x0E03, FACING_NE
    }};

    /**
     * Forgotten Tribe: Port types; will be shuffled.
     */
    private static final int FOR_TRI_PORT_TYPE[][] =
    {{
        // 4 players: 6 ports:
        CLAY_PORT, ORE_PORT, SHEEP_PORT, WHEAT_PORT, WOOD_PORT, MISC_PORT
    }, {
        // 6 players: 8 ports:
        CLAY_PORT, ORE_PORT, SHEEP_PORT, WHEAT_PORT, WOOD_PORT,
        MISC_PORT, MISC_PORT, MISC_PORT
    }};

    /**
     * Forgotten Tribe: Hex land types on the several small islands.
     * None have dice numbers.  Not shuffled; coordinates for these
     * land hexes are {@link #FOR_TRI_LANDHEX_COORD_ISL}.
     */
    private static final int FOR_TRI_LANDHEX_TYPE_ISL[][] =
    {{
        // 4 players: Clockwise from northwest corner of board:
        GOLD_HEX, ORE_HEX,    DESERT_HEX, ORE_HEX, WHEAT_HEX,    SHEEP_HEX,
        WOOD_HEX,    GOLD_HEX, DESERT_HEX, CLAY_HEX,    CLAY_HEX, DESERT_HEX
    }, {
        // 6 players: Northern islands west to east, then southern west to east:
        GOLD_HEX, WHEAT_HEX,    CLAY_HEX, ORE_HEX,    DESERT_HEX, DESERT_HEX,
        SHEEP_HEX, SHEEP_HEX,   GOLD_HEX, GOLD_HEX,   DESERT_HEX, DESERT_HEX
    }};

    /**
     * Forgotten Tribe: Land hex coordinates for the several small islands.
     * Hex types for these small islands are {@link #FOR_TRI_LANDHEX_TYPE_ISL}.
     * None have dice numbers.
     */
    private static final int FOR_TRI_LANDHEX_COORD_ISL[][] =
    {{
        // 4 players: Clockwise from northwest corner of board:
        0x0104, 0x0106,    0x010A, 0x010C, 0x010E,    0x0510,
        0x0910,    0x0D0E, 0x0D0C, 0x0D0A,    0x0D06, 0x0D04
    }, {
        // 6 players: Northern islands west to east, then southern west to east:
        0x0104, 0x0106,    0x010A, 0x010C,    0x0110, 0x0112,
        0x0D04, 0x0D06,    0x0D0A, 0x0D0C,    0x0D10, 0x0D12
    }};

    /**
     * Forgotten Tribe: Special Victory Point Edges: edge coordinates where ship placement gives an SVP.
     * SOCBoardLarge additional part {@code "VE"}.
     */
    private static final int FOR_TRI_SVP_EDGES[][] =
    {{
        // 4 players
        0x0004, 0x000A, 0x000E, 0x0511,
        0x0E04, 0x0E09, 0x0E0E, 0x0911
    }, {
        // 6 players
        0x0003, 0x0005, 0x000A, 0x000B, 0x0010,
        0x0E06, 0x0E0A, 0x0E0B, 0x0E10, 0x0E12
    }};

    /**
     * Forgotten Tribe: Dev Card Edges: Edge coordinates where ship placement gives a free development card.
     * SOCBoardLarge additional part {@code "CE"}.
     */
    private static final int FOR_TRI_DEV_CARD_EDGES[][] =
    {{
        // 4 players
        0x0103, 0x010F, 0x0D03, 0x0D0F
    }, {
        // 6 players
        0x0103, 0x000C, 0x0113, 0x0D03, 0x0E09, 0x0D13
    }};


    ////////////////////////////////////////////
    //
    // Nested class for board factory
    //


    /**
     * Server-side implementation of {@link BoardFactory} to create {@link SOCBoardLargeAtServer}s.
     * Called by game constructor via <tt>static {@link SOCGame#boardFactory}</tt>.
     * @author Jeremy D Monin
     * @since 2.0.00
     */
    public static class BoardFactoryAtServer implements BoardFactory
    {
        /**
         * Create a new Settlers of Catan Board based on <tt>gameOpts</tt>; this is a factory method.
         * Board size is based on <tt>maxPlayers</tt> and optional scenario (game option <tt>"SC"</tt>).
         *<P>
         * From v1.1.11 through 1.1.xx, this was SOCBoard.createBoard.  Moved to new factory class in 2.0.00.
         *
         * @param gameOpts  if game has options, its map of {@link SOCGameOption}; otherwise null.
         *                  If <tt>largeBoard</tt>, and
         *                  {@link SOCBoardLargeAtServer#getBoardSize(Map, int) getBoardSize(Map, int)}
         *                  gives a non-default size, <tt>"_BHW"</tt> will be added to <tt>gameOpts</tt>.
         * @param largeBoard  true if {@link SOCBoardLarge} should be used (v3 encoding)
         * @param maxPlayers Maximum players; must be 4 or 6.
         * @throws IllegalArgumentException if <tt>maxPlayers</tt> is not 4 or 6
         *                  or (unlikely internal error) game option "_BHW" isn't known in SOCGameOption.getOption.
         */
        public SOCBoard createBoard
            (final Map<String,SOCGameOption> gameOpts, final boolean largeBoard, final int maxPlayers)
            throws IllegalArgumentException
        {
            if (! largeBoard)
            {
                return SOCBoard.DefaultBoardFactory.staticCreateBoard(gameOpts, false, maxPlayers);
            } else {
                // Check board size, set _BHW if not default.
                final int boardHeightWidth = getBoardSize(gameOpts, maxPlayers);
                final int bH = boardHeightWidth >> 8, bW = boardHeightWidth & 0xFF;

                if (gameOpts != null)
                {
                    // gameOpts should never be null if largeBoard; largeBoard requires opt "PLL".
                    int bhw = 0;
                    SOCGameOption bhwOpt = gameOpts.get("_BHW");
                    if (bhwOpt != null)
                        bhw = bhwOpt.getIntValue();

                    if (((bH != SOCBoardLarge.BOARDHEIGHT_LARGE) || (bW != SOCBoardLarge.BOARDWIDTH_LARGE))
                        && (bhw != boardHeightWidth))
                    {
                        if (bhwOpt == null)
                            bhwOpt = SOCGameOption.getOption("_BHW", true);
                        if (bhwOpt != null)
                        {
                            bhwOpt.setIntValue(boardHeightWidth);
                            gameOpts.put("_BHW", bhwOpt);
                        } else {
                            throw new IllegalArgumentException("Internal error: Game opt _BHW not known");
                        }
                    }
                }

                return new SOCBoardLargeAtServer
                    (gameOpts, maxPlayers, new IntPair(bH, bW));
            }
        }

    }  // nested class BoardFactoryAtServer

}
