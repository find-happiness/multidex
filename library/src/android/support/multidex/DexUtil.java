package android.support.multidex;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.security.MessageDigest;

import dalvik.system.DexFile;

import static android.support.multidex.MultiDex.log;

/**
 * Tools to check odex file and remove if it was bad
 */
public class DexUtil {

    private static final String DEX_SUFFIX = ".dex";

    /**
     * verify the optimized dex file.<br/>
     * In some 4.x device, we found that dexopt generates bad odex occasionally which only contains header(40 bytes) and dex file content.
     * Dalvikvm log message like :
     * W/dalvikvm(23427): DexOpt: --- END '[package].apk.classes2.zip' --- status=0x000e, process failed
     * E/dalvikvm(23427): Unable to extract+optimize DEX from '/data/data/[package]/code_cache/secondary-dexes/[package].apk.classes2.zip'
     * Nothing useful for resolve the issue. So this method verify and delete the odex file if was bad for generating new odex file in next time call makeDexElements.
     *
     * @param dexZipFile
     * @param dexDir
     * @param element DexPathList.Element instance for close DexFile object
     * @return true if it is valid
     */
    public static boolean verify(File dexZipFile, File dexDir, Object element) {
        if (testDex(dexZipFile, dexDir)) {
            return true;
        }
        closeDexFile(dexZipFile, element);
        return false;
    }

    public static void closeDexFile(File zipFile, Object element) {
        if (element == null) {
            return;
        }
        try {
            Object dexFileValue = MultiDex.getFieldValue(element, "dexFile");
            if (dexFileValue instanceof DexFile) {
                DexFile dexFile = (DexFile) dexFileValue;
                String dexFileName = dexFile.getName();
                if (dexFileName.equals(zipFile.getAbsolutePath())) {
                    dexFile.close();
                }
            }
        } catch (Exception e) {
            log("can not get file dexFile from element ", e);
        }
    }

    /**
     * Converts a dex/jar file path and an output directory to an
     * output file path for an associated optimized dex file.
     */
    public static String optimizedPathFor(File path, File optimizedDirectory) {
        /*
         * Get the filename component of the path, and replace the
         * suffix with ".dex" if that's not already the suffix.
         *
         * We don't want to use ".odex", because the build system uses
         * that for files that are paired with resource-only jar
         * files. If the VM can assume that there's no classes.dex in
         * the matching jar, it doesn't need to open the jar to check
         * for updated dependencies, providing a slight performance
         * boost at startup. The use of ".dex" here matches the use on
         * files in /data/dalvik-cache.
         */
        String fileName = path.getName();
        if (!fileName.endsWith(DEX_SUFFIX)) {
            int lastDot = fileName.lastIndexOf(".");
            if (lastDot < 0) {
                fileName += DEX_SUFFIX;
            } else {
                StringBuilder sb = new StringBuilder(lastDot + 4);
                sb.append(fileName, 0, lastDot);
                sb.append(DEX_SUFFIX);
                fileName = sb.toString();
            }
        }
        File result = new File(optimizedDirectory, fileName);
        return result.getPath();
    }

    /**
     * magic(8), dexOffset(4), dexLength(4), depsOffset(4), depsLength(4), optOffset(4), optLength(4), flags(4), checksum(4)
     *
     * @param dexFile
     * @return header content of opt dex file in hex format
     */
    public static String headerOfDexFile(File dexFile) {
        DataInputStream dis = null;
        try {
            StringBuilder msg = new StringBuilder(40 * 2);
            dis = new DataInputStream(new FileInputStream(dexFile));
            for (int i = 0; i < 40; i++) {
                int data = dis.read();
                if (data == -1) {
                    break;
                }
                String value = Integer.toHexString(data);
                if (value.length() == 2) {
                    msg.append(value);
                } else {
                    msg.append("0")
                       .append(value);
                }
            }
            return msg.toString();
        } catch (IOException e) {
            return "read error " + e;
        } finally {
            if (dis != null) {
                try {
                    dis.close();
                } catch (IOException e) {
                    //ignore
                }
            }
        }
    }

    public static String rawDexMD5(File dexFile) {
        BufferedInputStream bis = null;
        try {
            bis = new BufferedInputStream(new FileInputStream(dexFile));
            byte[] buf = new byte[40];
            bis.read(buf);
            MessageDigest md = MessageDigest.getInstance("MD5");
            int len = 0;
            while ((len = bis.read(buf)) != -1) {
                md.digest(buf, 0, len);
            }
            return byteArrayToHex(md.digest());
        } catch (Exception e) {
            return "rawDexMD5 " + e;
        } finally {
            FileUtil.close(bis);
        }
    }


    public static String byteArrayToHex(byte[] byteArray) {
        char[] hexDigits = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
        char[] resultCharArray = new char[byteArray.length * 2];
        int index = 0;
        for (byte b : byteArray) {
            resultCharArray[index++] = hexDigits[b >>> 4 & 0xf];
            resultCharArray[index++] = hexDigits[b & 0xf];
        }
        return new String(resultCharArray);
    }

    public static boolean testDex(File zip, File dexDir) {
        String optimizedPath = optimizedPathFor(zip, dexDir);
        File optDexFile = new File(optimizedPath);
        if (!optDexFile.exists()) {
            return false;
        }
        try {
            DexFile.loadDex(zip.getCanonicalPath(), optimizedPath, 0);
            log("test load odex file " + optimizedPath + " success");
            return true;
        } catch (Exception e) {
            log("test load odex file " + optimizedPath + " failed, header content:" + headerOfDexFile(optDexFile) + ",rawDex md5:" + rawDexMD5(optDexFile) + ", delete file  " + optDexFile.delete());
            return false;
        }
    }

    public static boolean testDex(String zip, File dexDir) {
        return testDex(new File(zip), dexDir);
    }

    public static void deleteInvalid(File dexDir) {
        if (dexDir == null || !dexDir.exists()) {
            return;
        }

        File[] zips = dexDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.equals(".zip");
            }
        });
        if (zips == null || zips.length == 0) {
            return;
        }

        for (File zip : zips) {
            if (!testDex(zip, dexDir)) {
                log("MultiDexUtil.testDex delete invalid zip file " + zip + " " + zip.delete());
            }
        }
    }
}