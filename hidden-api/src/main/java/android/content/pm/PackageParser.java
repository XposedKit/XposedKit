package android.content.pm;

import java.io.File;
import java.util.ArrayList;

public class PackageParser {
    public Package parsePackage(File packageFile, int flags) throws PackageParserException {
        throw new RuntimeException("Stub!");
    }

    public static final class Package {
        public String packageName;
        public String[] splitNames;
        public String mVersionName;
        public int mVersionCode;
        public int mVersionCodeMajor;
        public int baseRevisionCode;
        public int[] splitRevisionCodes;
        public String mSharedUserId;
        public int mSharedUserLabel;
        public int installLocation;
        public ApplicationInfo applicationInfo;

        public ArrayList<ConfigurationInfo> configPreferences;
        public ArrayList<FeatureInfo> reqFeatures;
        public ArrayList<FeatureGroupInfo> featureGroups;

        public final ArrayList<Activity> activities;
        public final ArrayList<Activity> receivers;
        public final ArrayList<Service> services;
        public final ArrayList<Provider> providers;
        public final ArrayList<Instrumentation> instrumentation;

        public final ArrayList<Permission> permissions;
        public final ArrayList<String> requestedPermissions;

        public Package() {
            throw new RuntimeException("Stub!");
        }
    }

    public static final class Permission {
        public final PermissionInfo info;

        public Permission() {
            throw new RuntimeException("Stub!");
        }
    }

    public static final class Activity {
        public final ActivityInfo info;

        public Activity() {
            throw new RuntimeException("Stub!");
        }
    }

    public static final class Service {
        public final ServiceInfo info;

        public Service() {
            throw new RuntimeException("Stub!");
        }
    }

    public static final class Provider {
        public final ProviderInfo info;

        public Provider() {
            throw new RuntimeException("Stub!");
        }
    }

    public static final class Instrumentation {
        public final InstrumentationInfo info;

        public Instrumentation() {
            throw new RuntimeException("Stub!");
        }
    }

    public static class PackageParserException extends Exception {

    }
}
