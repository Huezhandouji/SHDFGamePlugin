package com.sHDFGamePlugin;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;

/** Paper 插件类加载器：预留动态库加载（当前无额外库） */
class SHDFGamePluginLoader implements PluginLoader {

    @Override
    public void classloader(final PluginClasspathBuilder builder) {
        // Add dynamically loaded libraries here
    }
}
