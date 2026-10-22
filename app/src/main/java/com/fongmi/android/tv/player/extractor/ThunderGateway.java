package com.fongmi.android.tv.player.extractor;

import com.fongmi.android.tv.exception.ExtractException;
import com.github.catvod.runtime.ThunderRuntime;
import com.github.catvod.utils.RuntimeModule;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

// 迅雷运行时网关：Thunder.java 唯一入口，不再直接 import 迅雷类。
// 顺序固定：先试外置包（RuntimeModule.THUNDER.entry → ThunderRuntime，
// 含 verifyPackage 签名校验与已编进宿主拒绝），接通后全程走它；
// 未接通（未安装／校验不过／加载失败）时回落直连——直连类只用反射调，
// 宿主编译期不依赖 :thunder，App 构建可以不把迅雷代码编进去。
// 直连是过渡：外置包接通且直连删除后，本类的 DirectRuntime 整段删掉，
// runtime() 里只剩 entry 一路；不要把回落写成与外置平行的永久口径。
// 注意 entry() 的失败缓存是上游行为：已安装后首次 entry 失败（如加载错）
// 会被 RuntimeModule 记下来、要重启才重进；未安装时的 MissingException
// 不进缓存，装好后下次 runtime() 能直接接通，不用重启。
public final class ThunderGateway {

    private static final ThunderGateway INSTANCE = new ThunderGateway();

    public static ThunderGateway get() {
        return INSTANCE;
    }

    // 状态取值：外置接口只有 taskStatus / errorMessage，没有速度与字节；
    // 直连侧 XLTaskInfo 有 mDownloadSpeed / mDownloadSize，定位日志要它们。
    // 外置路统一填 -1（未知），不编假数。
    public record StatusEx(int taskStatus, String errorMessage, long downloadSpeed, long downloadSize) {
    }

    private ThunderRuntime active;
    private boolean activeExternal;
    private DirectRuntime direct;
    private boolean directTried;

    private ThunderGateway() {
    }

    public synchronized ThunderRuntime runtime() throws ExtractException {
        if (active != null) return active;
        try {
            ThunderRuntime external = RuntimeModule.THUNDER.entry(ThunderRuntime.class);
            external.check();
            active = external;
            activeExternal = true;
            return active;
        } catch (RuntimeException | LinkageError | ExceptionInInitializerError ignored) {
            // 未接通：往下走直连回落；直连也没有才报模块未安装。
        }
        if (!directTried) {
            directTried = true;
            direct = DirectRuntime.tryCreate();
        }
        if (direct != null) {
            active = direct;
            activeExternal = false;
            return active;
        }
        throw new ExtractException("迅雷模块未安装，请在设置-运行模块里安装迅雷模块后重试");
    }

    public synchronized boolean isExternal() {
        return active != null && activeExternal;
    }

    // 只放已接通的运行时，不为 release 专门去接通一次。
    public synchronized void releaseQuietly() {
        if (active == null) return;
        try {
            active.release();
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    public synchronized StatusEx taskStatus(ThunderRuntime.Task task) throws ExtractException {
        ThunderRuntime runtime = runtime();
        if (activeExternal) {
            ThunderRuntime.Status status = runtime.getTaskInfo(task);
            return new StatusEx(status.taskStatus(), status.errorMessage(), -1L, -1L);
        }
        return ((DirectRuntime) runtime).rawTaskInfo(task);
    }

    public synchronized StatusEx btSubTaskStatus(ThunderRuntime.Task task, int index) throws ExtractException {
        ThunderRuntime runtime = runtime();
        if (activeExternal) {
            ThunderRuntime.Status status = runtime.getBtSubTaskInfo(task, index);
            return new StatusEx(status.taskStatus(), status.errorMessage(), -1L, -1L);
        }
        return ((DirectRuntime) runtime).rawBtSubTaskInfo(task, index);
    }

    // 直连适配：把宿主里已有的 XLTaskHelper（过渡编包才有）包成 ThunderRuntime。
    // 全部方法名与签名按 :thunder 现有 XLTaskHelper / 参数类取，类找不到
    // （已切外置的编包）时 tryCreate 回空，不抛，由网关报模块未安装。
    private static final class DirectRuntime implements ThunderRuntime {

        private final Object helper;
        private final Method parseMethod;
        private final Method addThunderTaskMethod;
        private final Method addTorrentTaskMethod;
        private final Method getTorrentInfoMethod;
        private final Method getLocalUrlMethod;
        private final Method deleteTaskMethod;
        private final Method stopTaskMethod;
        private final Method getTaskInfoMethod;
        private final Method getNativeTaskInfoMethod;
        private final Method getBtSubTaskInfoMethod;
        private final Method releaseMethod;
        private final Method checkMethod;
        private final Class<?> taskClass;
        private final Class<?> torrentInfoClass;

        static DirectRuntime tryCreate() {
            try {
                Class<?> helperClass = Class.forName("com.xunlei.downloadlib.XLTaskHelper");
                Object helper = helperClass.getMethod("get").invoke(null);
                Class<?> taskClass = Class.forName("com.xunlei.downloadlib.parameter.GetTaskId");
                return new DirectRuntime(helper, taskClass);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }

        private DirectRuntime(Object helper, Class<?> taskClass) throws ReflectiveOperationException {
            Class<?> helperClass = helper.getClass();
            this.helper = helper;
            this.taskClass = taskClass;
            this.torrentInfoClass = Class.forName("com.xunlei.downloadlib.parameter.TorrentInfo");
            parseMethod = helperClass.getMethod("parse", String.class, File.class);
            addThunderTaskMethod = helperClass.getMethod("addThunderTask", String.class, File.class);
            addTorrentTaskMethod = helperClass.getMethod("addTorrentTask", File.class, File.class, int.class);
            getTorrentInfoMethod = helperClass.getMethod("getTorrentInfo", File.class);
            getLocalUrlMethod = helperClass.getMethod("getLocalUrl", File.class);
            deleteTaskMethod = helperClass.getMethod("deleteTask", taskClass);
            stopTaskMethod = helperClass.getMethod("stopTask", taskClass);
            getTaskInfoMethod = helperClass.getMethod("getTaskInfo", taskClass);
            Method nativeMethod;
            try {
                nativeMethod = helperClass.getMethod("getNativeTaskInfo", taskClass);
            } catch (NoSuchMethodException e) {
                nativeMethod = null;
            }
            getNativeTaskInfoMethod = nativeMethod;
            getBtSubTaskInfoMethod = helperClass.getMethod("getBtSubTaskInfo", taskClass, int.class);
            releaseMethod = helperClass.getMethod("release");
            checkMethod = helperClass.getMethod("check");
        }

        @Override
        public void check() {
            invoke(checkMethod);
        }

        @Override
        public Task parse(String url, File savePath) {
            return (Task) invoke(parseMethod, url, savePath);
        }

        @Override
        public Task addThunderTask(String url, File savePath) {
            return (Task) invoke(addThunderTaskMethod, url, savePath);
        }

        @Override
        public Task addTorrentTask(File torrent, File savePath, int index) {
            return (Task) invoke(addTorrentTaskMethod, torrent, savePath, index);
        }

        @Override
        public List<Media> getTorrentInfo(File torrent) {
            try {
                Object info = invoke(getTorrentInfoMethod, torrent);
                Method medias = torrentInfoClass.getMethod("getMedias");
                List<?> list = (List<?>) medias.invoke(info);
                List<Media> result = new ArrayList<>();
                for (Object item : list) {
                    Class<?> itemClass = item.getClass();
                    String fileName = (String) itemClass.getMethod("getFileName").invoke(item);
                    String size = (String) itemClass.getMethod("getSize").invoke(item);
                    String playUrl = (String) itemClass.getMethod("getPlayUrl").invoke(item);
                    result.add(new Media(fileName, size, playUrl));
                }
                return result;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Thunder direct getTorrentInfo failed", e);
            }
        }

        @Override
        public String getLocalUrl(File file) {
            return (String) invoke(getLocalUrlMethod, file);
        }

        @Override
        public void deleteTask(Task task) {
            invoke(deleteTaskMethod, task);
        }

        @Override
        public void stopTask(Task task) {
            invoke(stopTaskMethod, task);
        }

        @Override
        public Status getTaskInfo(Task task) {
            return toStatus(invoke(getTaskInfoMethod, task));
        }

        @Override
        public Status getBtSubTaskInfo(Task task, int index) {
            return toStatus(taskInfoOf(invoke(getBtSubTaskInfoMethod, task, index)));
        }

        StatusEx rawTaskInfo(Task task) {
            if (getNativeTaskInfoMethod != null) return toStatusEx(invoke(getNativeTaskInfoMethod, task));
            Status status = getTaskInfo(task);
            return new StatusEx(status.taskStatus(), status.errorMessage(), -1L, -1L);
        }

        StatusEx rawBtSubTaskInfo(Task task, int index) {
            return toStatusEx(taskInfoOf(invoke(getBtSubTaskInfoMethod, task, index)));
        }

        private Object taskInfoOf(Object subDetail) {
            try {
                Field field = subDetail.getClass().getField("mTaskInfo");
                return field.get(subDetail);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Thunder direct BtSubTaskDetail failed", e);
            }
        }

        private Status toStatus(Object taskInfo) {
            try {
                Class<?> infoClass = taskInfo.getClass();
                int status = (int) infoClass.getMethod("getTaskStatus").invoke(taskInfo);
                String error = (String) infoClass.getMethod("getErrorMsg").invoke(taskInfo);
                return new Status(status, error);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Thunder direct task info failed", e);
            }
        }

        private StatusEx toStatusEx(Object taskInfo) {
            try {
                Class<?> infoClass = taskInfo.getClass();
                int status = (int) infoClass.getMethod("getTaskStatus").invoke(taskInfo);
                String error = (String) infoClass.getMethod("getErrorMsg").invoke(taskInfo);
                long speed = (long) infoClass.getField("mDownloadSpeed").get(taskInfo);
                long size = (long) infoClass.getField("mDownloadSize").get(taskInfo);
                return new StatusEx(status, error, speed, size);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Thunder direct task info failed", e);
            }
        }

        private Object invoke(Method method, Object... args) {
            try {
                return method.invoke(helper, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("Thunder direct call failed", cause);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Thunder direct call failed", e);
            }
        }
    }
}
