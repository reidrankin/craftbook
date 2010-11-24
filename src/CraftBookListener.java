// $Id$
/*
 * CraftBook
 * Copyright (C) 2010 sk89q <http://www.sk89q.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

import com.sk89q.craftbook.*;
import com.sk89q.craftbook.ic.*;
import java.io.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Event listener for Hey0's server mod.
 *
 * @author sk89q
 */
public class CraftBookListener extends PluginListener {
    /**
     * Logger.
     */
    private static final Logger logger = Logger.getLogger("Minecraft");
    /**
     * Properties file for CraftBook.
     */
    private PropertiesFile properties = new PropertiesFile("craftbook.properties");
    /**
     * Used for toggle-able areas.
     */
    private CopyManager copies = new CopyManager();
    /**
     * Stores sessions.
     */
    private Map<String,CraftBookSession> sessions =
            new HashMap<String,CraftBookSession>();
    /**
     * SISO ICs.
     */
    private Map<String,SISOFamilyIC> sisoICs =
            new HashMap<String,SISOFamilyIC>();

    /**
     * Indicates whether each function should check permissions.
     */
    private boolean checkPermissions;
    /**
     * Maximum toggle area size.
     */
    private int maxToggleAreaSize;

    /**
     * Fast block bag access.
     */
    private static BlockBag dummyBlockBag = new UnlimitedBlackHoleBlockBag();

    private boolean useChestsBlockBag = false;
    private BookReader readingModule;
    private String bookReadLine;
    private Cauldron cauldronModule;
    private Elevator elevatorModule;
    private GateSwitch gateSwitchModule;
    private boolean redstoneGates = true;
    private LightSwitch lightSwitchModule;
    private Bridge bridgeModule;
    private boolean redstoneBridges = true;
    private boolean useToggleAreas;
    private boolean dropBookshelves = true;
    private boolean redstonePumpkins = true;
    private double dropAppleChance = 0;
    private boolean redstoneICs = true;
    private boolean enableAmmeter = true;

    /**
     * Checks to make sure that there are enough but not too many arguments.
     *
     * @param args
     * @param min
     * @param max -1 for no maximum
     * @param cmd command name
     * @throws InsufficientArgumentsException
     */
    private void checkArgs(String[] args, int min, int max, String cmd)
            throws InsufficientArgumentsException {
        if (args.length <= min) {
            throw new InsufficientArgumentsException("Minimum " + min + " arguments");
        } else if (max != -1 && args.length - 1 > max) {
            throw new InsufficientArgumentsException("Maximum " + max + " arguments");
        }
    }

    /**
     * Convert a comma-delimited list to a set of integers.
     *
     * @param str
     * @return
     */
    private static Set<Integer> toBlockIDSet(String str) {
        if (str.trim().length() == 0) {
            return null;
        }

        String[] items = str.split(",");
        Set<Integer> result = new HashSet<Integer>();

        for (String item : items) {
            try {
                result.add(Integer.parseInt(item.trim()));
            } catch (NumberFormatException e) {
                int id = etc.getDataSource().getItem(item.trim());
                if (id != 0) {
                    result.add(id);
                } else {
                    logger.log(Level.WARNING, "CraftBook: Unknown block name: "
                            + item);
                }
            }
        }

        return result;
    }

    /**
     * Construct the object.
     * 
     */
    public CraftBookListener() {
        sisoICs.put("MC1017", new MC1017());
        sisoICs.put("MC1020", new MC1020());
    }

    /**
     * Loads CraftBooks's configuration from file.
     */
    public void loadConfiguration() {
        properties.load();

        maxToggleAreaSize = Math.max(0, properties.getInt("toggle-area-max-size", 5000));

        readingModule = properties.getBoolean("bookshelf-enable", true) ? new BookReader() : null;
        bookReadLine = properties.getString("bookshelf-read-text", "You pick out a book...");
        lightSwitchModule = properties.getBoolean("light-switch-enable", true) ? new LightSwitch() : null;
        gateSwitchModule = properties.getBoolean("gate-enable", true) ? new GateSwitch() : null;
        redstoneGates = properties.getBoolean("gate-redstone", true);
        elevatorModule = properties.getBoolean("elevators-enable", true) ? new Elevator() : null;
        bridgeModule = properties.getBoolean("bridge-enable", true) ? new Bridge() : null;
        redstoneBridges = properties.getBoolean("bridge-redstone", true);
        Bridge.allowableBridgeBlocks = toBlockIDSet(properties.getString("bridge-blocks", "4,5,20,43"));
        Bridge.maxBridgeLength = properties.getInt("bridge-max-length", 30);
        dropBookshelves = properties.getBoolean("drop-bookshelves", true);
        try {
            dropAppleChance = Double.parseDouble(properties.getString("apple-drop-chance", "0.5")) / 100.0;
        } catch (NumberFormatException e) {
            dropAppleChance = -1;
            logger.log(Level.WARNING, "Invalid apple drop chance setting in craftbook.properties");
        }
        useToggleAreas = properties.getBoolean("toggle-areas-enable", true);
        redstonePumpkins = properties.getBoolean("redstone-pumpkins", true);
        checkPermissions = properties.getBoolean("check-permissions", false);
        cauldronModule = null;
        redstoneICs = properties.getBoolean("redstone-ics", true);
        enableAmmeter = properties.getBoolean("ammeter", true);

        String blockBag = properties.getString("block-bag", "unlimited-black-hole");
        if (blockBag.equalsIgnoreCase("nearby-chests")) {
            useChestsBlockBag = true;
        } else if (blockBag.equalsIgnoreCase("unlimited-black-hole")) {
            useChestsBlockBag = false;
        } else {
            logger.log(Level.WARNING, "Unknown CraftBook block bag: " + blockBag);
            useChestsBlockBag = false;
        }

        if (properties.getBoolean("cauldron-enable", true)) {
            try {
                CauldronCookbook recipes =
                        readCauldronRecipes("cauldron-recipes.txt");

                if (recipes.size() != 0) {
                    cauldronModule = new Cauldron(recipes);
                    logger.log(Level.INFO, recipes.size() + " cauldron recipe(s) loaded");
                } else {
                    logger.log(Level.WARNING, "cauldron-recipes.txt had no recipes");
                }
            } catch (IOException e) {
                logger.log(Level.INFO, "cauldron-recipes.txt not loaded: "
                        + e.getMessage());
            }
        }
    }

    /**
     * Called when a block is hit with the primary attack.
     * 
     * @param player
     * @param block
     * @return
     */
    @Override
    public boolean onBlockDestroy(Player player, Block block) {
        // Random apple drops
        if (dropAppleChance > 0 && block.getType() == BlockType.LEAVES
                && checkPermission(player, "/appledrops")) {
            if (block.getStatus() == 3) {
                if (Math.random() <= dropAppleChance) {
                    player.giveItemDrop(ItemType.APPLE, 1);
                }
            }

        // Bookshelf drops
        } else if (dropBookshelves && block.getType() == BlockType.BOOKCASE
                && checkPermission(player, "/bookshelfdrops")) {
            if (block.getStatus() == 3) {
                player.giveItemDrop(BlockType.BOOKCASE, 1);
            }
        }

        return false;
    }

    /**
     * Called when a block is being attempted to be placed.
     *
     * @param player
     * @param blockPlaced
     * @param blockClicked
     * @param itemInHand
     * @return
     */
    @Override
    public boolean onBlockCreate(Player player, Block blockPlaced,
            Block blockClicked, int itemInHand) {
        try {
            return doBlockCreate(player, blockPlaced, blockClicked, itemInHand);
        } catch (OutOfBlocksException e) {
            player.sendMessage(Colors.Rose + "Uh oh! Ran out of: " + toBlockName(e.getID()));
            player.sendMessage(Colors.Rose + "Make sure nearby chests have the necessary materials.");
        } catch (OutOfSpaceException e) {
            player.sendMessage(Colors.Rose + "No room left to put: " + toBlockName(e.getID()));
            player.sendMessage(Colors.Rose + "Make sure nearby partially occupied chests have free slots.");
        } catch (BlockBagException e) {
            player.sendMessage(Colors.Rose + "Unknown error: " + e.getMessage());
        }

        return true; // On error
    }

    /**
     * Called when a block is being attempted to be placed.
     * 
     * @param player
     * @param blockPlaced
     * @param blockClicked
     * @param itemInHand
     * @return
     */
    private boolean doBlockCreate(Player player, Block blockPlaced,
            Block blockClicked, int itemInHand) throws BlockBagException {

        int current = -1;

        if (enableAmmeter && itemInHand == 263) { // Coal
            int type = blockClicked.getType();
            int data = CraftBook.getBlockData(blockClicked.getX(),
                    blockClicked.getY(), blockClicked.getZ());
            
            if (type == BlockType.LEVER) {
                if ((data & 0x8) == 0x8) {
                    current = 15;
                }
                current = 0;
            } else if (type == BlockType.STONE_PRESSURE_PLATE) {
                if ((data & 0x1) == 0x1) {
                    current = 15;
                }
                current = 0;
            } else if (type == BlockType.WOODEN_PRESSURE_PLATE) {
                if ((data & 0x1) == 0x1) {
                    current = 15;
                }
                current = 0;
            } else if (type == BlockType.REDSTONE_TORCH_ON) {
                current = 15;
            } else if (type == BlockType.REDSTONE_TORCH_OFF) {
                current = 0;
            } else if (type == BlockType.STONE_BUTTON) {
                if ((data & 0x8) == 0x8) {
                    current = 15;
                }
                current = 0;
            } else if (type == BlockType.REDSTONE_WIRE) {
                current = data;
            }

            if (current > -1) {
                player.sendMessage(Colors.Yellow + "Ammeter: "
                        + Colors.Yellow + "["
                        + Colors.Yellow + repeatString("|", current)
                        + Colors.Black + repeatString("|", 15 - current)
                        + Colors.Yellow + "] "
                        + Colors.White
                        + current + " A");
            } else {
                player.sendMessage(Colors.Yellow + "Ammeter: " + Colors.Red + "Not supported.");
            }

            return false;
        }

        // Discriminate against attempts that would actually place blocks
        boolean isPlacingBlock = blockPlaced.getType() != -1
                && blockPlaced.getType() <= 256;

        int plyX = (int)Math.floor(player.getLocation().x);
        int plyY = (int)Math.floor(player.getLocation().y);
        int plyZ = (int)Math.floor(player.getLocation().z);

        // Book reading
        if (readingModule != null
                && blockClicked.getType() == BlockType.BOOKCASE
                && !isPlacingBlock
                && checkPermission(player, "/readbooks")) {
            readingModule.readBook(player, bookReadLine);
            return true;

        // Sign buttons
        } else if (blockClicked.getType() == BlockType.WALL_SIGN ||
                blockClicked.getType() == BlockType.SIGN_POST ||
                CraftBook.getBlockID(plyX, plyY + 1, plyZ) == BlockType.WALL_SIGN ||
                CraftBook.getBlockID(plyX, plyY, plyZ) == BlockType.WALL_SIGN) {
            int x = blockClicked.getX();
            int y = blockClicked.getY();
            int z = blockClicked.getZ();

            Vector pt;
            if (blockClicked.getType() == BlockType.WALL_SIGN
                    || blockClicked.getType() == BlockType.SIGN_POST) {
                pt = new Vector(x, y, z);
            } else if (CraftBook.getBlockID(plyX, plyY + 1, plyZ) == BlockType.WALL_SIGN) {
                pt = new Vector(plyX, plyY + 1, plyZ);
                x = plyX;
                y = plyY + 1;
                z = plyZ;
            } else {
                pt = new Vector(plyX, plyY, plyZ);
                x = plyX;
                y = plyY;
                z = plyZ;
            }

            ComplexBlock cBlock = etc.getServer().getComplexBlock(x, y, z);

            if (cBlock instanceof Sign) {
                Sign sign = (Sign)cBlock;
                String line2 = sign.getText(1);

                // Gate
                if (gateSwitchModule != null && line2.equalsIgnoreCase("[Gate]")
                        && checkPermission(player, "/gate")) {
                    BlockBag bag = getBlockBag(pt);
                    bag.addSourcePosition(pt);

                    // A gate may toggle or not
                    if (gateSwitchModule.toggleGates(pt, bag)) {
                        player.sendMessage(Colors.Gold + "*screeetch* Gate moved!");
                    } else {
                        player.sendMessage(Colors.Rose + "No nearby gate to toggle.");
                    }
                
                // Light switch
                } else if (lightSwitchModule != null &&
                        (line2.equalsIgnoreCase("[|]") || line2.equalsIgnoreCase("[I]"))
                        && checkPermission(player, "/lightswitch")) {
                    BlockBag bag = getBlockBag(pt);
                    bag.addSourcePosition(pt);
                    return lightSwitchModule.toggleLights(pt, bag);

                // Elevator
                } else if (elevatorModule != null
                        && (line2.equalsIgnoreCase("[Lift Up]")
                        || line2.equalsIgnoreCase("[Lift Down]"))
                        && checkPermission(player, "/elevator)")) {

                    // Go up or down?
                    boolean up = line2.equalsIgnoreCase("[Lift Up]");
                    elevatorModule.performLift(player, pt, up);
                    return true;

                // Toggle areas
                } else if (useToggleAreas != false
                        && line2.equalsIgnoreCase("[Toggle]")) {
                    String name = sign.getText(0);

                    if (name.trim().length() == 0) {
                        player.sendMessage(Colors.Rose + "Area name must be the first line.");
                        return true;
                    } else if (!CopyManager.isValidName(name)) {
                        player.sendMessage(Colors.Rose + "Not a valid area name (1st sign line)!");
                        return true;
                    }

                    try {
                        BlockBag bag = getBlockBag(pt);
                        bag.addSourcePosition(pt);
                        CuboidCopy copy = copies.load(name);
                        if (copy.distance(pt) <= 4) {
                            copy.toggle(bag);
                            
                            // Get missing
                            Map<Integer,Integer> missing = bag.getMissing();
                            if (missing.size() > 0) {
                                for (Map.Entry<Integer,Integer> entry : missing.entrySet()) {
                                    player.sendMessage(Colors.Rose + "Missing "
                                            + entry.getValue() + "x "
                                            + toBlockName(entry.getKey()));
                                }
                            } else {
                                player.sendMessage(Colors.Gold + "Toggled!");
                            }
                        } else {
                            player.sendMessage(Colors.Rose + "This sign is too far away!");
                        }
                    } catch (CuboidCopyException e) {
                        player.sendMessage(Colors.Rose + "Could not load area: " + e.getMessage());
                    } catch (IOException e2) {
                        player.sendMessage(Colors.Rose + "Could not load area: " + e2.getMessage());
                    }

                // Bridges
                } else if (bridgeModule != null
                        && blockClicked.getType() == BlockType.SIGN_POST
                        && line2.equalsIgnoreCase("[Bridge]")) {
                    int data = CraftBook.getBlockData(x, y, z);

                    try {
                        BlockBag bag = getBlockBag(pt);
                        bag.addSourcePosition(pt);
                        
                        if (data == 0x0) {
                            bridgeModule.toggleBridge(new Vector(x, y, z), Bridge.Direction.EAST, bag);
                            player.sendMessage(Colors.Gold + "Bridge toggled.");
                        } else if (data == 0x4) {
                            bridgeModule.toggleBridge(new Vector(x, y, z), Bridge.Direction.SOUTH, bag);
                            player.sendMessage(Colors.Gold + "Bridge toggled.");
                        } else if (data == 0x8) {
                            bridgeModule.toggleBridge(new Vector(x, y, z), Bridge.Direction.WEST, bag);
                            player.sendMessage(Colors.Gold + "Bridge toggled.");
                        } else if (data == 0xC) {
                            bridgeModule.toggleBridge(new Vector(x, y, z), Bridge.Direction.NORTH, bag);
                            player.sendMessage(Colors.Gold + "Bridge toggled.");
                        } else {
                            player.sendMessage(Colors.Rose + "That sign is not in a right direction.");
                        }
                    } catch (OperationException e) {
                        player.sendMessage(Colors.Rose + e.getMessage());
                    }
                }
            }

        // Cauldron
        } else if (cauldronModule != null
                && checkPermission(player, "/cauldron")
                && (blockPlaced.getType() == -1 || blockPlaced.getType() >= 256)
                && blockPlaced.getType() != BlockType.STONE) {
            
            int x = blockClicked.getX();
            int y = blockClicked.getY();
            int z = blockClicked.getZ();

            cauldronModule.preCauldron(new Vector(x, y, z), player);
        }

        return false;
    }

    /*
    * Called whenever a redstone source (wire, switch, torch) changes its
    * current.
    *
    * Standard values for wires are 0 for no current, and 14 for a strong current.
    * Default behaviour for redstone wire is to lower the current by one every
    * block.
    *
    * For other blocks which provide a source of redstone current, the current
    * value will be 1 or 0 for on and off respectively.
    *
    * @param redstone Block of redstone which has just changed in current
    * @param oldLevel the old current
    * @param newLevel the new current
    */
    public void onRedstoneChange(Block block, int oldLevel, int newLevel) {
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        boolean wasOn = oldLevel >= 1;
        boolean isOn = newLevel >= 1;
        boolean wasChange = wasOn != isOn;

        if (!wasChange) {
            return;
        }

        int type = CraftBook.getBlockID(x, y + 1, z);            
        // Pre-check for efficiency
        if (redstoneGates || redstoneBridges || redstonePumpkins) {
            if (wasOn != isOn) {
                // Can power nearby blocks
                for (int cx = x - 1; cx <= x + 1; cx++) {
                    for (int cy = y - 1; cy <= y + 1; cy++) {
                        for (int cz = z - 1; cz <= z + 1; cz++) {
                            if (cx != x || cy != y || cz != z) {
                                try {
                                    testRedstoneInput(cx, cy, cz, isOn);
                                } catch (BlockBagException e) { }
                            }
                        }
                    }
                }
            }
        }

        if (redstoneICs) {
            int westSide = CraftBook.getBlockID(x, y, z + 1);
            int eastSide = CraftBook.getBlockID(x, y, z - 1);
            int northSide = CraftBook.getBlockID(x - 1, y, z);
            int southSide = CraftBook.getBlockID(x + 1, y, z);

            if (westSide != BlockType.REDSTONE_WIRE
                    || eastSide != BlockType.REDSTONE_WIRE) {
                // Possible blocks north / south
                handleWireInput(x - 1, y, z, isOn);
                handleWireInput(x + 1, y, z, isOn);
            }
            
            if (northSide != BlockType.REDSTONE_WIRE
                    || southSide != BlockType.REDSTONE_WIRE) {
                // Possible blocks west / east
                handleWireInput(x, y, z - 1, isOn);
                handleWireInput(x, y, z + 1, isOn);
            }
        }
    }

    /**
     * Handles the wire input at a block.
     * @param x
     * @param y
     * @param z
     * @param isOn
     */
    public void handleWireInput(int x, int y, int z, boolean isOn) {
        int type = CraftBook.getBlockID(x, y, z);

        // ICs
        if (type == BlockType.WALL_SIGN) {
            ComplexBlock cblock = etc.getServer().getComplexBlock(x, y, z);

            if (!(cblock instanceof Sign)) {
                return;
            }

            Sign sign = (Sign)cblock;
            String line2 = sign.getText(1);
            int len = line2.length();

            // SISO family
            if (line2.substring(0, 3).equals("[MC") &&
                    line2.charAt(len - 1) == ']') {
                SISOFamilyIC ic = sisoICs.get(line2.substring(1, len - 1));

                if (ic != null) {
                    Vector backVec = getWallSignBack(x, y, z);
                    Vector outputVec = backVec.add(0, 1, 0);
                    int backBlock = CraftBook.getBlockID(backVec);

                    boolean lastState =
                            CraftBook.getBlockID(outputVec) == BlockType.REDSTONE_TORCH_ON;
                    boolean newState = ic.think(new Vector(x, y, z), isOn, lastState);

                    if (newState != lastState) {
                        if (!newState && CraftBook.getBlockID(outputVec) == BlockType.REDSTONE_TORCH_ON) {
                            CraftBook.setBlockID(outputVec, BlockType.AIR);
                        } else if (newState && CraftBook.getBlockID(outputVec) == BlockType.AIR) {
                            CraftBook.setBlockID(outputVec, BlockType.REDSTONE_TORCH_ON);
                        }

                        // Won't cause a trigger
                        /*if (CraftBook.getBlockID(outputVec) == BlockType.LEVER) {
                            int data = CraftBook.getBlockData(outputVec);
                            if (!newState) {
                                CraftBook.setBlockData(outputVec, data & 0x7);
                            } else {
                                CraftBook.setBlockData(outputVec, data | 0x8);
                            }
                        }*/
                    }
                }
            }
        }
    }

    /**
     * Test a block as a redstone input block.
     * 
     * @param x
     * @param y
     * @param z
     * @param isOn
     * @throws BlockBagException
     */
    public void testRedstoneInput(int x, int y, int z, boolean isOn)
        throws BlockBagException {
        int type = CraftBook.getBlockID(x, y, z);

        // Sign triggers
        if (type == BlockType.WALL_SIGN || type == BlockType.SIGN_POST) {
            ComplexBlock cblock = etc.getServer().getComplexBlock(x, y, z);

            // Always have to do this check
            if (!(cblock instanceof Sign)) return;

            Vector pt = new Vector(x, y, z);
            Sign sign = (Sign)cblock;
            String line2 = sign.getText(1);

            // Gate
            if (gateSwitchModule != null && redstoneGates
                    && line2.equalsIgnoreCase("[Gate]")) {
                BlockBag bag = getBlockBag(pt);
                bag.addSourcePosition(pt);

                // A gate may toggle or not
                gateSwitchModule.setGateState(pt, bag, isOn);

            // Bridges
            } else if (bridgeModule != null
                    && redstoneBridges
                    && type == BlockType.SIGN_POST
                    && line2.equalsIgnoreCase("[Bridge]")) {
                int data = CraftBook.getBlockData(x, y, z);

                try {
                    BlockBag bag = getBlockBag(pt);
                    bag.addSourcePosition(pt);

                    if (data == 0x0) {
                        bridgeModule.setBridgeState(new Vector(x, y, z), Bridge.Direction.EAST, bag, !isOn);
                    } else if (data == 0x4) {
                        bridgeModule.setBridgeState(new Vector(x, y, z), Bridge.Direction.SOUTH, bag, !isOn);
                    } else if (data == 0x8) {
                        bridgeModule.setBridgeState(new Vector(x, y, z), Bridge.Direction.WEST, bag, !isOn);
                    } else if (data == 0xC) {
                        bridgeModule.setBridgeState(new Vector(x, y, z), Bridge.Direction.NORTH, bag, !isOn);
                    }
                } catch (OperationException e) {
                }
            }
        // Redstone pumpkins
        } else if (redstonePumpkins
                && (type == BlockType.PUMPKIN || type == BlockType.JACKOLANTERN)) {
            Boolean useOn = testSKDRedstoneInput(x, y, z);

            if (useOn != null && useOn) {
                CraftBook.setBlockID(x, y, z, BlockType.JACKOLANTERN);
            } else if (useOn != null) {
                CraftBook.setBlockID(x, y, z, BlockType.PUMPKIN);
            }
        }
    }

    /**
     * Attempts to detect redstone input.
     * 
     * @param x
     * @param y
     * @param z
     * @return
     */
    public Boolean testSKDRedstoneInput(int x, int y, int z) {
        Boolean result = null;
        Boolean temp = null;

        // Check block above
        int above = CraftBook.getBlockID(x, y + 1, z);
        temp = testRedstoneTrigger(x, y + 1, z, above);
        if (temp != null) {
            if (temp == true) {
                return true;
            } else {
                result = false;
            }
        }

        // Check block below
        int below = CraftBook.getBlockID(x, y - 1, z);
        temp = testRedstoneTrigger(x, y - 1, z, below);
        if (temp != null) {
            if (temp == true) {
                return true;
            } else {
                result = false;
            }
        }

        int north = CraftBook.getBlockID(x - 1, y, z);
        int south = CraftBook.getBlockID(x + 1, y, z);
        int west = CraftBook.getBlockID(x, y, z + 1);
        int east = CraftBook.getBlockID(x, y, z - 1);

        // For wires that lead up to only this block
        if (north == BlockType.REDSTONE_WIRE) {
            int side1 = CraftBook.getBlockID(x - 1, y, z - 1);
            int side2 = CraftBook.getBlockID(x - 1, y, z + 1);

            if (!BlockType.isRedstoneBlock(side1) && !BlockType.isRedstoneBlock(side2)) {
                if (CraftBook.getBlockData(x - 1, y, z) > 0) {
                    return true;
                }
                result = false;
            }
        }

        if (south == BlockType.REDSTONE_WIRE) {
            int side1 = CraftBook.getBlockID(x + 1, y, z - 1);
            int side2 = CraftBook.getBlockID(x + 1, y, z + 1);

            if (!BlockType.isRedstoneBlock(side1) && !BlockType.isRedstoneBlock(side2)) {
                if (CraftBook.getBlockData(x + 1, y, z) > 0) {
                    return true;
                }
            }
            result = false;
        }

        if (west == BlockType.REDSTONE_WIRE) {
            int side1 = CraftBook.getBlockID(x + 1, y, z + 1);
            int side2 = CraftBook.getBlockID(x - 1, y, z + 1);

            if (!BlockType.isRedstoneBlock(side1) && !BlockType.isRedstoneBlock(side2)) {
                if (CraftBook.getBlockData(x, y, z + 1) > 0) {
                    return true;
                }
            }
            result = false;
        }

        if (east == BlockType.REDSTONE_WIRE) {
            int side1 = CraftBook.getBlockID(x + 1, y, z - 1);
            int side2 = CraftBook.getBlockID(x - 1, y, z - 1);

            if (!BlockType.isRedstoneBlock(side1) && !BlockType.isRedstoneBlock(side2)) {
                if (CraftBook.getBlockData(x, y, z - 1) > 0) {
                    return true;
                }
            }
            result = false;
        }

        // The sides of the block below
        temp = testRedstoneTrigger(x - 1, y - 1, z, north);
        if (temp != null) {
            if (temp == true) {
                return true;
            } else {
                result = false;
            }
        }

        temp = testRedstoneTrigger(x + 1, y - 1, z, south);
        if (temp != null) {
            if (temp == true) {
                return true;
            } else {
                result = false;
            }
        }
        
        temp = testRedstoneTrigger(x, y - 1, z + 1, west);
        if (temp != null) {
            if (temp == true) {
                return true;
            } else {
                result = false;
            }
        }

        temp = testRedstoneTrigger(x, y - 1, z - 1, east);
        if (temp != null) {
            if (temp == true) {
                return true;
            } else {
                result = false;
            }
        }

        return result;
    }

    /**
     * Tests a redstone trigger (everything but wire) to see if it's
     * indicatign a true. May return a null if there was no data.
     * 
     * @param x
     * @param y
     * @param z
     * @param type
     * @return
     */
    private Boolean testRedstoneTrigger(int x, int y, int z, int type) {
        Boolean result = null;
        
        if (type == BlockType.LEVER) {
            if ((CraftBook.getBlockData(x, y, z) & 0x8) == 0x8) {
                return true;
            }
            result = false;
        } else if (type == BlockType.STONE_PRESSURE_PLATE) {
            if ((CraftBook.getBlockData(x, y, z) & 0x1) == 0x1) {
                return true;
            }
            result = false;
        } else if (type == BlockType.WOODEN_PRESSURE_PLATE) {
            if ((CraftBook.getBlockData(x, y, z) & 0x1) == 0x1) {
                return true;
            }
            result = false;
        } else if (type == BlockType.REDSTONE_TORCH_ON) {
            return true;
        } else if (type == BlockType.REDSTONE_TORCH_OFF) {
            result = false;
        } else if (type == BlockType.STONE_BUTTON) {
            if ((CraftBook.getBlockData(x, y, z) & 0x8) == 0x8) {
                return true;
            }
            result = false;
        }

        return result;
    }

    /**
     *
     * @param player
     * @param split
     * @return whether the command was processed
     */
    @Override
    public boolean onCommand(Player player, String[] split) {
        try {
            return runCommand(player, split);
        } catch (InsufficientArgumentsException e) {
            player.sendMessage(Colors.Rose + e.getMessage());
            return true;
        } catch (LocalWorldEditBridgeException e) {
            if (e.getCause() != null) {
                Throwable cause = e.getCause();
                if (cause.getClass().getCanonicalName().equals("com.sk89q.worldedit.IncompleteRegionException")) {
                    player.sendMessage(Colors.Rose + "Region not fully defined (via WorldEdit).");
                } else if (cause.getClass().getCanonicalName().equals("com.sk89q.worldedit.WorldEditNotInstalled")) {
                    player.sendMessage(Colors.Rose + "The WorldEdit plugin is not loaded.");
                } else {
                    player.sendMessage(Colors.Rose + "Unknown CraftBook<->WorldEdit error: " + cause.getClass().getCanonicalName());
                }
            } else {
                player.sendMessage(Colors.Rose + "Unknown CraftBook<->WorldEdit error: " + e.getMessage());
            }

            return true;
        }
    }
    
    /**
     *
     * @param player
     * @param split
     * @return whether the command was processed
     */
    public boolean runCommand(Player player, String[] split)
            throws InsufficientArgumentsException, LocalWorldEditBridgeException {
        if (split[0].equalsIgnoreCase("/savearea") && canUse(player, "/savearea")) {
            checkArgs(split, 1, -1, split[0]);

            String name = joinString(split, " ", 1);

            if (!CopyManager.isValidName(name)) {
                player.sendMessage(Colors.Rose + "Invalid area name.");
                return true;
            }
            
            try {
                Vector min = LocalWorldEditBridge.getRegionMinimumPoint(player);
                Vector max = LocalWorldEditBridge.getRegionMaximumPoint(player);
                Vector size = max.subtract(min).add(1, 1, 1);

                if (size.getBlockX() * size.getBlockY() * size.getBlockZ() > maxToggleAreaSize) {
                    player.sendMessage(Colors.Rose + "Area is larger than allowed "
                            + maxToggleAreaSize + " blocks.");
                    return true;
                }

                CuboidCopy copy = new CuboidCopy(min, size);
                copy.copy();
                try {
                    copies.save(name, copy);
                    player.sendMessage(Colors.Gold + "Area saved as '" + name + "'");
                } catch (IOException e) {
                    player.sendMessage(Colors.Rose + "Could not save area: " + e.getMessage());
                }
            } catch (NoClassDefFoundError e) {
                player.sendMessage(Colors.Rose + "WorldEdit.jar does not exist in plugins/.");
            }

            return true;
        }

        return false;
    }

    /**
     * Get a block bag.
     * 
     * @param origin
     * @return
     */
    public BlockBag getBlockBag(Vector origin) {
        if (useChestsBlockBag) {
            return new NearbyChestBlockBag(origin);
        } else {
            return dummyBlockBag;
        }
    }
    
    /**
     *
     * @param player
     */
    @Override
    public void onDisconnect(Player player) {
        sessions.remove(player.getName());
    }

    /**
     * Get the CraftBook session associated with a player. A new session
     * will be created if needed.
     *
     * @param player
     * @return
     */
    public CraftBookSession getSession(Player player) {
        if (sessions.containsKey(player.getName())) {
            return sessions.get(player.getName());
        } else {
            CraftBookSession session = new CraftBookSession();
            sessions.put(player.getName(), session);
            return session;
        }
    }

    /**
     * Check if a player can use a command.
     *
     * @param player
     * @param command
     * @return
     */
    public boolean canUse(Player player, String command) {
        return player.canUseCommand(command);
    }

    /**
     * Check if a player can use a command. May be overrided if permissions
     * checking is disabled.
     * 
     * @param player
     * @param command
     * @return
     */
    public boolean checkPermission(Player player, String command) {
        return !checkPermissions || player.canUseCommand(command);
    }

    /**
     * Gets the block behind a sign.
     *
     * @param x
     * @param y
     * @param z
     * @return
     */
    public static Vector getWallSignBack(int x, int y, int z) {
        int data = CraftBook.getBlockData(x, y, z);
        if (data == 0x2) { // East
            return new Vector(x, y, z + 1);
        } else if (data == 0x3) { // West
            return new Vector(x, y, z - 1);
        } else if (data == 0x4) { // North
            return new Vector(x + 1, y, z );
        } else {
            return new Vector(x - 1, y, z);
        }
    }

    /**
     * Change a block ID to its name.
     * 
     * @param id
     * @return
     */
    private static String toBlockName(int id) {
        com.sk89q.worldedit.blocks.BlockType blockType =
                com.sk89q.worldedit.blocks.BlockType.fromID(id);

        if (blockType == null) {
            return "#" + id;
        } else {
            return blockType.getName();
        }
    }

    /**
     * Parse a list of cauldron items.
     * 
     * @param list
     * @return
     */
    private static List<Integer> parseCauldronItems(String list) {
        String[] parts = list.split(",");

        List<Integer> out = new ArrayList<Integer>();

        for (String part : parts) {
            int multiplier = 1;

            try {
                // Multiplier
                if (part.matches("^.*\\*([0-9]+)$")) {
                    int at = part.lastIndexOf("*");
                    multiplier = Integer.parseInt(
                            part.substring(at + 1, part.length()));
                    part = part.substring(0, at);
                }

                try {
                    for (int i = 0; i < multiplier; i++) {
                        out.add(Integer.valueOf(part));
                    }
                } catch (NumberFormatException e) {
                    int item = etc.getDataSource().getItem(part);

                    if (item > 0) {
                        for (int i = 0; i < multiplier; i++) {
                            out.add(item);
                        }
                    } else {
                        logger.log(Level.WARNING, "Cauldron: Unknown item " + part);
                    }
                }
            } catch (NumberFormatException e) { // Bad multiplier
                logger.log(Level.WARNING, "Cauldron: Bad multiplier in '" + part + "'");
            }
        }

        return out;
    }

    /**
     * Read a file containing cauldron recipes.
     *
     * @param file
     * @return
     * @throws IOException
     */
    private static CauldronCookbook readCauldronRecipes(String path)
            throws IOException {
        File file = new File(path);
        FileReader input = null;
        CauldronCookbook cookbook = new CauldronCookbook();

        try {
            input = new FileReader(file);
            BufferedReader buff = new BufferedReader(input);

            String line;
            while ((line = buff.readLine()) != null) {
                line = line.trim();

                // Blank lines
                if (line.length() == 0) {
                    continue;
                }

                // Comment
                if (line.charAt(0) == ';' || line.charAt(0) == '#' || line.equals("")) {
                    continue;
                }

                String[] parts = line.split(":");
                
                if (parts.length < 3) {
                    logger.log(Level.WARNING, "Invalid cauldron recipe line in "
                            + file.getName() + ": '" + line + "'");
                } else {
                    String name = parts[0];
                    List<Integer> ingredients = parseCauldronItems(parts[1]);
                    List<Integer> results = parseCauldronItems(parts[2]);
                    
                    CauldronRecipe recipe =
                            new CauldronRecipe(name, ingredients, results);
                    cookbook.add(recipe);
                }
            }

            return cookbook;
        } finally {
            try {
                if (input != null) {
                    input.close();
                }
            } catch (IOException e) {
            }
        }
    }

    /**
     * Joins a string from an array of strings.
     *
     * @param str
     * @param delimiter
     * @return
     */
    private static String joinString(String[] str, String delimiter,
            int initialIndex) {
        if (str.length == 0) {
            return "";
        }
        StringBuilder buffer = new StringBuilder(str[initialIndex]);
        for (int i = initialIndex + 1; i < str.length; i++) {
            buffer.append(delimiter).append(str[i]);
        }
        return buffer.toString();
    }

    /**
     * Repeat a string.
     * 
     * @param string
     * @param num
     * @return
     */
    private static String repeatString(String str, int num) {
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < num; i++) {
            buffer.append(str);
        }
        return buffer.toString();
    }
}