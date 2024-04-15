/*
 * Copyright 2015-2016 Michael Kocherov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * This code is imported to SELogger to avoid invalid bytecode
 */

package io.unlogged.weaver;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

public class MetracerClassWriter extends ClassWriter {
    private ClassLoader classLoader;

    public MetracerClassWriter(ClassReader theReader, ClassLoader theLoader) {
        super(theReader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        classLoader = theLoader;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        try {
            String commonSuperClass = super.getCommonSuperClass(type1, type2);
            return commonSuperClass;
        } catch (Throwable e) {
//            e.printStackTrace();
            return "java/lang/Object";
        }
    }
}
