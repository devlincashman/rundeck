/*
 * Copyright 2018 Rundeck, Inc. (http://rundeck.com)
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
package org.rundeck.plugin.azureobjectstore.directorysource

import com.dtolabs.rundeck.core.storage.BaseStreamResource
import com.microsoft.azure.storage.CloudStorageAccount
import com.microsoft.azure.storage.blob.CloudBlobClient
import com.microsoft.azure.storage.blob.CloudBlobContainer
import com.microsoft.azure.storage.blob.CloudBlobDirectory
import com.microsoft.azure.storage.blob.CloudBlockBlob
import com.microsoft.azure.storage.blob.ListBlobItem
import io.minio.MinioClient
import io.minio.errors.ErrorResponseException
import io.minio.messages.Item
import org.rundeck.plugin.azureobjectstore.stream.LazyAccessObjectStoreInputStream
import org.rundeck.plugin.azureobjectstore.tree.ObjectStoreResource
import org.rundeck.plugin.azureobjectstore.tree.ObjectStoreTree
import org.rundeck.plugin.azureobjectstore.tree.ObjectStoreUtils
import org.rundeck.storage.api.Resource

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Uses the object client to directly access the object store to get directory information.
 * For stores with lots of objects this could be a very inefficient directory access mechanism.
 *
 * This store works best when the object store is going to be accessed by multiple cluster members
 * or the object store is regularly updated by third party tools
 */
class ObjectStoreDirectAccessDirectorySource implements ObjectStoreDirectorySource {
    private static final String DIR_MARKER = "/"
    CloudBlobContainer container

    ObjectStoreDirectAccessDirectorySource(CloudBlobContainer container) {
        this.container = container
    }

    CloudBlockBlob getBlobFile(String path){
        CloudBlockBlob blob = container.getBlockBlobReference(path);
        return blob
    }

    boolean checkBlob(String path){
        try {
            CloudBlockBlob blob = getBlobFile(path)
            if(blob==null){
                return false
            }
            return blob.exists()
        } catch (Exception e) {
            //logger.log(Level.SEVERE, e.getMessage());
            //logger.log(Level.FINE, e.getMessage(), e);
            throw new Exception(e.getMessage(), e)
        }

        return false
    }

    @Override
    boolean checkPathExists(final String path) {
        checkBlob(path)
    }

    @Override
    boolean checkResourceExists(final String path) {
        checkBlob(path)
    }

    @Override
    boolean checkPathExistsAndIsDirectory(final String path) {
        boolean directory = false


        container.listBlobs(path).each { object ->
            if(object instanceof CloudBlobDirectory){
                directory = true
            }
        }

        return directory

        //container.listBlobs(path)
        //def items = mClient.listObjects(bucket, path, false)
        //if(items.size() != 1) return false
        //return items[0].get().objectName().endsWith(DIR_MARKER)
    }

    @Override
    Map<String, String> getEntryMetadata(final String path) {
        CloudBlockBlob blob = getBlobFile(path)
        if(blob==null){
            return null
        }
        return ObjectStoreUtils.objectStatToMap(blob)
    }

    @Override
    Set<Resource<BaseStreamResource>> listSubDirectoriesAt(final String path) {
        def resources = []
        def subdirs = [] as Set
        String lstPath = path == "" ? null : path
        Pattern directSubDirMatch = ObjectStoreUtils.createSubdirCheckForPath(lstPath)
        /*
        container.listBlobs().each { object ->
            if(object instanceof CloudBlobDirectory){
                CloudBlobDirectory folder = (CloudBlobDirectory) object
                list.add([name:folder.getUri().toString(),
                          container:folder.getContainer().getName(),
                          uri:"",
                          lastModified:"",
                          length:"",
                          type:"FOLDER",
                          contentType:""])

                if(recursive){
                    list.addAll(listBlobs(folder.listBlobs()))
                }

            }else{
                list.add(printBlob(object))
            }

        }
         */

        Iterable<ListBlobItem> items = container.listBlobs().each { result ->
            Matcher m = directSubDirMatch.matcher(result.uri) //manipulate to get name?)
            if(m.matches()) {
                subdirs.add(m.group(1))
            }
        }
        subdirs.sort().each { String dirname -> resources.add(new ObjectStoreResource(path+ "/"+dirname, null, true)) }
        return resources
    }

    @Override
    Set<Resource<BaseStreamResource>> listEntriesAndSubDirectoriesAt(final String path) {
        def resources = []
        Pattern directSubDirMatch = ObjectStoreUtils.createSubdirCheckForPath(path)
        def subdirs = [] as Set

        /*
        container.listBlobs().each { object ->
            if(object instanceof CloudBlobDirectory){
                CloudBlobDirectory folder = (CloudBlobDirectory) object
                list.add([name:folder.getUri().toString(),
                          container:folder.getContainer().getName(),
                          uri:"",
                          lastModified:"",
                          length:"",
                          type:"FOLDER",
                          contentType:""])

                if(recursive){
                    list.addAll(listBlobs(folder.listBlobs()))
                }

            }else{
                list.add(printBlob(object))
            }

        }
         */

        /*
        mClient.listObjects(bucket, path, true).each { result ->
            Matcher m = directSubDirMatch.matcher(result.get().objectName())
            if(m.matches()) {
                subdirs.add(path+DIR_MARKER+m.group(1))
            } else {
                resources.add(createResourceListItemWithMetadata(result.get()))
            }
        }

         */
        subdirs.sort().each { String dirname -> resources.add(new ObjectStoreResource(dirname, null, true)) }
        return resources
    }

    @Override
    Set<Resource<BaseStreamResource>> listResourceEntriesAt(final String path) {
        def resources = []
        String lstPath = path == "" ? null : path
        String rPath = path == "" ?: path+"/"

        /*
        container.listBlobs(path).each { object ->
            if(!(object instanceof CloudBlobDirectory)){
                directory = true
            }
        }


        mClient.listObjects(bucket, lstPath, true)
               .findAll {
            !(it.get().objectName().replaceAll(rPath,"").contains("/"))
        }.each { result ->
            resources.add(createResourceListItemWithMetadata(result.get()))
        }

         */
        return resources
    }

    private ObjectStoreResource createResourceListItemWithMetadata(final CloudBlockBlob item) {
        BaseStreamResource content = new BaseStreamResource(getEntryMetadata(item.getName()),
                                     new LazyAccessObjectStoreInputStream(item))
        return new ObjectStoreResource(item.getName(), content)
    }

    @Override
    void updateEntry(final String fullEntryPath, final Map<String, String> meta) {
        //no-op no additional action needed
    }

    @Override
    void deleteEntry(final String fullEntryPath) {
        //no-op no additional action needed
    }

    @Override
    void resyncDirectory() {
        //no-op
    }
}
