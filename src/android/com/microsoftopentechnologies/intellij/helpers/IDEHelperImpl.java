package com.microsoftopentechnologies.intellij.helpers;

import com.google.common.util.concurrent.SettableFuture;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.microsoftopentechnologies.intellij.components.AppSettingsNames;
import com.microsoftopentechnologies.intellij.components.DefaultLoader;
import com.microsoftopentechnologies.intellij.forms.OpenSSLFinderForm;
import com.microsoftopentechnologies.intellij.helpers.aadauth.BrowserLauncher;
import com.microsoftopentechnologies.intellij.helpers.aadauth.LauncherTask;
import com.microsoftopentechnologies.intellij.helpers.storage.BlobExplorerFileEditorProvider;
import com.microsoftopentechnologies.intellij.model.storage.BlobContainer;
import com.microsoftopentechnologies.intellij.model.storage.StorageAccount;
import com.microsoftopentechnologies.intellij.serviceexplorer.BackgroundLoader;
import com.microsoftopentechnologies.intellij.serviceexplorer.Node;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class IDEHelperImpl implements IDEHelper {

    @Override
    public void openFile(File file, final Node node) {
        final VirtualFile finalEditfile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
        ApplicationManager.getApplication().runReadAction(new Runnable() {
            @Override
            public void run() {
                try {
                    openFile(node.getProject(), finalEditfile);
                } finally {
                    node.setLoading(false);
                }
            }
        });
    }

    @Override
    public void runInBackground(Object project, String name, boolean canBeCancelled, final boolean isIndeterminate, final String indicatorText, final Runnable runnable) {
        ProgressManager.getInstance().run(new Task.Backgroundable((Project) project, "Creating blob container...", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                if (isIndeterminate) {
                    indicator.setIndeterminate(true);
                }
                if (indicatorText != null) {
                    indicator.setText(indicatorText);
                }
                runnable.run();
            }
        });
    }

    private void openFile(final Object projectObject, final VirtualFile finalEditfile) {
        try {
            if (finalEditfile != null) {
                finalEditfile.setWritable(true);

                ApplicationManager.getApplication().invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        FileEditorManager.getInstance((Project) projectObject).openFile(finalEditfile, true);
                    }
                });
            }
        } catch (Throwable e) {
            DefaultLoader.getUIHelper().showException("Error writing temporal editable file:", e);
        }
    }

    public void saveFile(final File file, final ByteArrayOutputStream buff, final Node node) {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
            @Override
            public void run() {
                try {

                    final VirtualFile editfile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
                    if (editfile != null) {
                        editfile.setWritable(true);

                        editfile.setBinaryContent(buff.toByteArray());

                        ApplicationManager.getApplication().invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                FileEditorManager.getInstance((Project)node.getProject()).openFile(editfile, true);
                            }
                        });
                    }
                } catch (Throwable e) {
                    DefaultLoader.getUIHelper().showException("Error writing temporal editable file:", e);
                } finally {
                    node.setLoading(false);
                }
            }
        });
    }

    public void replaceInFile(Object moduleObject, Pair<String, String>... replace) {
        Module module = (Module) moduleObject;
        if (module.getModuleFile() != null && module.getModuleFile().getParent() != null) {
            VirtualFile vf = module.getModuleFile().getParent().findFileByRelativePath(ServiceCodeReferenceHelper.STRINGS_XML);

            if (vf != null) {
                FileDocumentManager fdm = FileDocumentManager.getInstance();
                com.intellij.openapi.editor.Document document = fdm.getDocument(vf);

                if (document != null) {
                    String content = document.getText();
                    for (Pair<String, String> pair : replace) {
                        content = content.replace(pair.getLeft(), pair.getRight());
                    }
                    document.setText(content);
                    fdm.saveDocument(document);
                }
            }
        }
    }

    public void copyJarFiles2Module(Object moduleObject, File zipFile) throws IOException {
        Module module = (Module) moduleObject;
        final VirtualFile moduleFile = module.getModuleFile();

        if (moduleFile != null) {
            moduleFile.refresh(false, false);

            final VirtualFile moduleDir = module.getModuleFile().getParent();

            if (moduleDir != null) {
                moduleDir.refresh(false, false);

                copyJarFiles(module, moduleDir, zipFile, ServiceCodeReferenceHelper.NOTIFICATIONHUBS_PATH);
            }
        }
    }

    private void copyJarFiles(final Module module, VirtualFile baseDir, File zipFile, String zipPath) throws IOException {
        if (baseDir.isDirectory()) {
            final ZipFile zip = new ZipFile(zipFile);
            Enumeration<? extends ZipEntry> entries = zip.entries();

            while (entries.hasMoreElements()) {
                final ZipEntry zipEntry = entries.nextElement();

                if (!zipEntry.isDirectory() && zipEntry.getName().startsWith(zipPath) &&
                        zipEntry.getName().endsWith(".jar") &&
                        !(zipEntry.getName().endsWith("-sources.jar") || zipEntry.getName().endsWith("-javadoc.jar"))) {
                    VirtualFile libsVf = null;

                    for (VirtualFile vf : baseDir.getChildren()) {
                        if (vf.getName().equals("libs")) {
                            libsVf = vf;
                            break;
                        }
                    }

                    if (libsVf == null) {
                        libsVf = baseDir.createChildDirectory(module.getProject(), "libs");
                    }

                    final VirtualFile libs = libsVf;

                    ApplicationManager.getApplication().invokeAndWait(new Runnable() {
                        @Override
                        public void run() {
                            ApplicationManager.getApplication().runWriteAction(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        InputStream mobileserviceInputStream = zip.getInputStream(zipEntry);
                                        VirtualFile msVF = libs.createChildData(module.getProject(), zipEntry.getName().split("/")[1]);
                                        msVF.setBinaryContent(getArray(mobileserviceInputStream));
                                    } catch (Throwable ex) {
                                        DefaultLoader.getUIHelper().showException("Error trying to configure Azure Mobile Services", ex);
                                    }
                                }
                            });
                        }
                    }, ModalityState.defaultModalityState());
                }
            }
        }
    }

    private byte[] getArray(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int nRead;
        byte[] data = new byte[16384];

        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return buffer.toByteArray();
    }

    public boolean isFileEditing(Object projectObject, File file) {
        VirtualFile scriptFile = LocalFileSystem.getInstance().findFileByIoFile(file);
        boolean fileIsEditing = false;

        if (scriptFile != null)
            fileIsEditing = FileEditorManager.getInstance((Project) projectObject).getEditors(scriptFile).length != 0;
        return fileIsEditing;
    }

    public void openContainer(@NotNull Object projectObject, StorageAccount storageAccount, BlobContainer blobContainer) {

        if (getOpenedFile(projectObject, storageAccount, blobContainer) == null) {

            LightVirtualFile containerVirtualFile = new LightVirtualFile(blobContainer.getName() + " [Container]");
            containerVirtualFile.putUserData(BlobExplorerFileEditorProvider.CONTAINER_KEY, blobContainer);
            containerVirtualFile.putUserData(BlobExplorerFileEditorProvider.STORAGE_KEY, storageAccount);

            containerVirtualFile.setFileType(new FileType() {
                @NotNull
                @Override
                public String getName() {
                    return "BlobContainer";
                }

                @NotNull
                @Override
                public String getDescription() {
                    return "BlobContainer";
                }

                @NotNull
                @Override
                public String getDefaultExtension() {
                    return "";
                }

                @Nullable
                @Override
                public Icon getIcon() {
                    return DefaultLoader.getUIHelper().loadIcon("container.png");
                }

                @Override
                public boolean isBinary() {
                    return true;
                }

                @Override
                public boolean isReadOnly() {
                    return false;
                }

                @Override
                public String getCharset(@NotNull VirtualFile virtualFile, @NotNull byte[] bytes) {
                    return "UTF8";
                }
            });

            FileEditorManager.getInstance((Project) projectObject).openFile(containerVirtualFile, true, true);
        }
    }

    public Object getOpenedFile(Object projectObject, StorageAccount storageAccount, BlobContainer blobContainer) {
        FileEditorManager fileEditorManager = FileEditorManager.getInstance((Project) projectObject);

        for (VirtualFile editedFile : fileEditorManager.getOpenFiles()) {
            BlobContainer editedBlobContainer = editedFile.getUserData(BlobExplorerFileEditorProvider.CONTAINER_KEY);
            StorageAccount editedStorageAccount = editedFile.getUserData(BlobExplorerFileEditorProvider.STORAGE_KEY);

            if(editedStorageAccount != null
                    && editedBlobContainer != null
                    && editedStorageAccount.getName().equals(storageAccount.getName())
                    && editedBlobContainer.getName().equals(blobContainer.getName())) {
                return editedFile;
            }
        }
        return null;
    }

    public void closeFile(Object projectObject, Object openedFile) {
        FileEditorManager.getInstance((Project) projectObject).closeFile((VirtualFile) openedFile);
    }

    public void invokeAuthLauncherTask(Object projectObject, BrowserLauncher browserLauncher, String windowTitle) {
        LauncherTask task = new LauncherTask(browserLauncher, (Project) projectObject, windowTitle, true);
        task.queue();
    }

    public void invokeBackgroundLoader(final Object projectObject, final Node node, final SettableFuture<List<Node>> future, final String name) {
        // background tasks via ProgressManager can be scheduled only on the
        // dispatch thread
        ApplicationManager.getApplication().invokeLater(new Runnable() {
            @Override
            public void run() {
                ProgressManager.getInstance().run(new BackgroundLoader(node, future, (Project) projectObject, name, false));
            }
        });
    }

    public void invokeLater(Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(runnable);
    }

    public void invokeAndWait(Runnable runnable) {
        ApplicationManager.getApplication().invokeAndWait(runnable, ModalityState.any());
    }

    public void executeOnPooledThread(Runnable runnable) {
        ApplicationManager.getApplication().executeOnPooledThread(runnable);
    }

    public String getProperty(String name) {
        return PropertiesComponent.getInstance().getValue(name);
    }

    public String getProperty(String name, String defaultValue) {
        PropertiesComponent pc = PropertiesComponent.getInstance();
        return pc.getValue(name, defaultValue);
    }

    public void setProperty(String name, String value) {
        PropertiesComponent.getInstance().setValue(name, value);
    }

    public void unsetProperty(String name) {
        PropertiesComponent.getInstance().unsetValue(name);
    }

    public boolean isPropertySet(String name) {
        return PropertiesComponent.getInstance().isValueSet(name);
    }

    public String promptForOpenSSLPath() {
        OpenSSLFinderForm openSSLFinderForm = new OpenSSLFinderForm();
        openSSLFinderForm.setModal(true);
        DefaultLoader.getUIHelper().packAndCenterJDialog(openSSLFinderForm);
        openSSLFinderForm.setVisible(true);

        return getProperty("MSOpenSSLPath", "");
    }
}
