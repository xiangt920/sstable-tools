package cn.ac.iie.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * 文件操作相关工具类
 *
 * @author Xiang
 * @date 2016-09-25 14:25
 */
public class FileUtils {
    private static final Logger LOG = LoggerFactory.getLogger(FileUtils.class);

    /**
     * 若目录不存在时创建目录
     * @param dir 目录文件对象
     * @return 返回创建结果
     * 若文件存在且文件为目录则返回true；<br/>
     * 若文件不存在则递归创建目录且返回创建结果；<br/>
     * 否则，返回false。
     */
    public static boolean createDirIfNotExists(File dir) {
        boolean exists = dir.exists();
        return exists && dir.isDirectory() || !exists && dir.mkdirs();
    }

    /**
     * 删除指定文件
     * @param file 指定待删除文件
     * @return 返回是否删除成功
     */
    public static boolean deleteFile(File file) {
        return file.exists() && file.delete();
    }

    /**
     * 迁移文件
     * @param sourcePath 源文件路径
     * @param targetPath 目标文件路径
     */
    public static boolean copyFile(Path sourcePath, Path targetPath){
        boolean copied = false;
        try {
            // 将源文件移动至目标文件，同时需要拷贝文件属性
            Files.copy(sourcePath, targetPath, REPLACE_EXISTING, COPY_ATTRIBUTES);
            LOG.info("成功将文件{} 拷贝至{}", sourcePath.toString(), targetPath.toString());
            copied = true;
        } catch (IOException e) {
            LOG.error("文件拷贝出现IO异常，源文件：{}，目标文件：{}", sourcePath.toString(), targetPath.toString());
            LOG.error(e.getMessage(), e);
        } catch (Exception e) {
            LOG.error("文件拷贝出现其它异常，源文件：{}，目标文件：{}", sourcePath.toString(), targetPath.toString());
            LOG.error(e.getMessage(), e);
        }
        return copied;
    }

    /**
     * 创建软连接
     * @param linkPath 连接文件路径
     * @param targetPath 目标文件路径
     * @return 返回是否创建成功
     * 当出现IO异常或其它异常时将会创建失败
     */
    public static boolean createSymbolicLink(Path linkPath, Path targetPath){
        boolean created = true;
        try {
            Files.createSymbolicLink(
                    linkPath,
                    targetPath);
            LOG.info("成功创建软连接{}，连接目标：{}", linkPath.toString(), targetPath.toString());
        } catch (IOException e) {
            LOG.error("创建软连接{} 出现IO异常，连接目标：{}", linkPath.toString(), targetPath.toString());
            LOG.error(e.getMessage(), e);
            created = false;
        } catch (Exception e){
            LOG.error("创建软连接{} 出现其它异常，连接目标：{}", linkPath.toString(), targetPath.toString());
            LOG.error(e.getMessage(), e);
            created = false;

        }
        return created;
    }
}