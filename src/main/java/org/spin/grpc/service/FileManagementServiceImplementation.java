/************************************************************************************
 * Copyright (C) 2018-2023 E.R.P. Consultores y Asociados, C.A.                     *
 * Contributor(s): Edwin Betancourt, EdwinBetanc0urt@outlook.com                    *
 * This program is free software: you can redistribute it and/or modify             *
 * it under the terms of the GNU General Public License as published by             *
 * the Free Software Foundation, either version 2 of the License, or                *
 * (at your option) any later version.                                              *
 * This program is distributed in the hope that it will be useful,                  *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of                   *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the                     *
 * GNU General Public License for more details.                                     *
 * You should have received a copy of the GNU General Public License                *
 * along with this program. If not, see <https://www.gnu.org/licenses/>.            *
 ************************************************************************************/
package org.spin.grpc.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.core.domains.models.I_C_Invoice;
import org.compiere.model.MAttachment;
import org.compiere.model.MClientInfo;
import org.compiere.model.MTable;
import org.compiere.model.Query;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.compiere.util.MimeType;
import org.compiere.util.Trx;
import org.compiere.util.Util;
import org.spin.backend.grpc.common.Empty;
import org.spin.backend.grpc.file_management.Attachment;
import org.spin.backend.grpc.file_management.DeleteResourceReferenceRequest;
import org.spin.backend.grpc.file_management.ExistsAttachmentRequest;
import org.spin.backend.grpc.file_management.ExistsAttachmentResponse;
import org.spin.backend.grpc.file_management.FileManagementGrpc.FileManagementImplBase;
import org.spin.backend.grpc.file_management.GetAttachmentRequest;
import org.spin.backend.grpc.file_management.GetResourceReferenceRequest;
import org.spin.backend.grpc.file_management.GetResourceRequest;
import org.spin.backend.grpc.file_management.LoadResourceRequest;
import org.spin.backend.grpc.file_management.Resource;
import org.spin.backend.grpc.file_management.ResourceReference;
import org.spin.backend.grpc.file_management.SetResourceReferenceDescriptionRequest;
import org.spin.backend.grpc.file_management.SetResourceReferenceRequest;
import org.spin.base.util.FileUtil;
import org.spin.base.util.RecordUtil;
import org.spin.base.util.ValueUtil;
import org.adempiere.core.domains.models.I_AD_AttachmentReference;
import org.spin.model.MADAttachmentReference;
import org.spin.util.AttachmentUtil;

import com.google.protobuf.ByteString;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.ByteBuffer;

/**
 * @author Edwin Betancourt, EdwinBetanc0urt@outlook.com, https://github.com/EdwinBetanc0urt
 * Service for backend of File Management (Attanchment)
 */
public class FileManagementServiceImplementation extends FileManagementImplBase {
	/**	Logger			*/
	private CLogger log = CLogger.getCLogger(FileManagementServiceImplementation.class);
	
	public String tableName = I_C_Invoice.Table_Name;


	/**
	 * Validate client info exists and with configured file handler.
	 * @return clientInfo
	 */
	private MClientInfo validateAndGetClientInfo() {
		MClientInfo clientInfo = MClientInfo.get(Env.getCtx());
		if (clientInfo == null || clientInfo.getAD_Client_ID() < 0 || clientInfo.getFileHandler_ID() <= 0) {
			throw new AdempiereException("@FileHandler_ID@ @NotFound@");
		}
		return clientInfo;
	}



	/**
	 * Validate table exists.
	 * @return clientInfo
	 */
	private MTable validateAndGetTable(String tableName) {
		// validate table
		if (Util.isEmpty(tableName, true)) {
			throw new AdempiereException("@FillMandatory@ @AD_Table_ID@");
		}
		MTable table = MTable.get(Env.getCtx(), tableName);
		if (table == null || table.getAD_Table_ID() <= 0) {
			throw new AdempiereException("@AD_Table_ID@ @NotFound@");
		}
		return table;
	}


	@Override
	public void getResource(GetResourceRequest request, StreamObserver<Resource> responseObserver) {
		try {
			if (request == null || (Util.isEmpty(request.getResourceUuid(), true) && Util.isEmpty(request.getResourceName(), true))) {
				throw new AdempiereException("Object Request Null");
			}
			log.fine("Download Requested = " + request.getResourceUuid());
			//	Get resource
			getResource(request.getResourceUuid(), request.getResourceName(), responseObserver);
		} catch (Exception e) {
			log.severe(e.getLocalizedMessage());
			e.printStackTrace();
			responseObserver.onError(Status.INTERNAL
				.withDescription(e.getLocalizedMessage())
				.withCause(e)
				.asRuntimeException()
			);
		}
	}

	public String getResourceUuidFromName(String resourceName) {
		if (Util.isEmpty(resourceName, true)) {
			return null;
		}

		MClientInfo clientInfo = MClientInfo.get(Env.getCtx());
		MADAttachmentReference reference = new Query(
				Env.getCtx(),
				I_AD_AttachmentReference.Table_Name,
				"(UUID || '-' || FileName) = ? AND FileHandler_ID = ?",
				null
			)
			.setOrderBy(I_AD_AttachmentReference.COLUMNNAME_AD_Attachment_ID + " DESC")
			.setParameters(resourceName, clientInfo.getFileHandler_ID())
			.first();

		if (reference == null || reference.getAD_AttachmentReference_ID() <= 0) {
			return null;
		}
		return reference.getUUID();
	}
	
	/**
	 * Get File from fileName
	 * @param resourceUuid
	 * @param responseObserver
	 * @throws Exception 
	 */
	private void getResource(String resourceUuid, String resourceName, StreamObserver<Resource> responseObserver) throws Exception {
		if (!AttachmentUtil.getInstance().isValidForClient(Env.getAD_Client_ID(Env.getCtx()))) {
			responseObserver.onError(new AdempiereException("@NotFound@"));
			return;
		}

		//	Validate by name
		if (Util.isEmpty(resourceUuid, true)) {
			resourceUuid = getResourceUuidFromName(resourceName);
			if (Util.isEmpty(resourceUuid, true)) {
				responseObserver.onError(new AdempiereException("@NotFound@"));
				return;
			}
		}
		int attachmentReferenceId = RecordUtil.getIdFromUuid(I_AD_AttachmentReference.Table_Name, resourceUuid, null);
		byte[] data = AttachmentUtil.getInstance()
			.withClientId(Env.getAD_Client_ID(Env.getCtx()))
			.withAttachmentReferenceId(attachmentReferenceId)
			.getAttachment();
		if (data == null) {
			responseObserver.onError(new AdempiereException("@NotFound@"));
			return;
		}
		//	For all
		int bufferSize = 256 * 1024; // 256k
		byte[] buffer = new byte[bufferSize];
		int length;
		InputStream is = new ByteArrayInputStream(data);
		while ((length = is.read(buffer, 0, bufferSize)) != -1) {
			Resource builder = Resource.newBuilder()
				.setData(ByteString.copyFrom(buffer, 0, length))
				.build()
			;
			responseObserver.onNext(
				builder
			);
		}
		//	Completed
		responseObserver.onCompleted();
	}


	@Override
	public void getResourceReference(GetResourceReferenceRequest request, StreamObserver<ResourceReference> responseObserver) {
		try {
			if (request == null) {
				throw new AdempiereException("Object Request Null");
			}
			ResourceReference.Builder resourceReference = getResourceReferenceFromImageId(request.getImageId());
			responseObserver.onNext(resourceReference.build());
			responseObserver.onCompleted();
		} catch (Exception e) {
			log.severe(e.getLocalizedMessage());
			e.printStackTrace();
			responseObserver.onError(Status.INTERNAL
				.withDescription(e.getLocalizedMessage())
				.withCause(e)
				.asRuntimeException()
			);
		}
	}

	/**
	 * Get resource from Image Id
	 * @param imageId
	 * @return
	 */
	private ResourceReference.Builder getResourceReferenceFromImageId(int imageId) {
		return convertResourceReference(
			FileUtil.getResourceFromImageId(imageId)
		);
	}


	@Override
	public StreamObserver<LoadResourceRequest> loadResource(StreamObserver<ResourceReference> responseObserver) {
		AtomicReference<String> resourceUuid = new AtomicReference<>();
		AtomicReference<ByteBuffer> buffer = new AtomicReference<>();
		return new StreamObserver<LoadResourceRequest>() {
			@Override
			public void onNext(LoadResourceRequest fileUploadRequest) {
				try {
					if(resourceUuid.get() == null) {
						resourceUuid.set(fileUploadRequest.getResourceUuid());
						BigDecimal size = ValueUtil.getBigDecimalFromDecimal(fileUploadRequest.getFileSize());
						if (size != null && fileUploadRequest.getData() != null) {
							byte[] initByte = new byte[size.intValue()];
							buffer.set(ByteBuffer.wrap(initByte));
							byte[] bytes = fileUploadRequest.getData().toByteArray();
							buffer.set(buffer.get().put(bytes));
						}
					} else if (buffer.get() != null){
						byte[] bytes = fileUploadRequest.getData().toByteArray();
						buffer.set(buffer.get().put(bytes));
					}
				} catch (Exception e){
					e.printStackTrace();
					this.onError(e);
				}
			}

			@Override
			public void onError(Throwable throwable) {
				responseObserver.onError(throwable);
			}

			@Override
			public void onCompleted() {
				try {
					// validate and get client info with configured file handler
					MClientInfo clientInfo = validateAndGetClientInfo();

					ResourceReference.Builder response = ResourceReference.newBuilder();
					if(resourceUuid.get() != null && buffer.get() != null) {
						MADAttachmentReference resourceReference = MADAttachmentReference.getByUuid(
							Env.getCtx(),
							resourceUuid.get(),
							null
						);
						if (resourceReference != null) {
							byte[] data = buffer.get().array();
							AttachmentUtil.getInstance()
								.clear()
								.withAttachmentReferenceId(resourceReference.getAD_AttachmentReference_ID())
								.withFileName(resourceReference.getFileName())
								.withClientId(clientInfo.getAD_Client_ID())
								.withData(data)
								.saveAttachment();

							MADAttachmentReference.resetAttachmentReferenceCache(clientInfo.getFileHandler_ID(), resourceReference);
							response = convertResourceReference(resourceReference);
						}
					}

					responseObserver.onNext(response.build());
					responseObserver.onCompleted();
				} catch (Exception e) {
					e.printStackTrace();
					throw new AdempiereException(e);
				}
			}
		};
	}

	@Override
	public void getAttachment(GetAttachmentRequest request, StreamObserver<Attachment> responseObserver) {
		try {
			if (request == null) {
				throw new AdempiereException("Object Request Null");
			}

			Attachment.Builder attachment = getAttachmentFromEntity(request);
			responseObserver.onNext(attachment.build());
			responseObserver.onCompleted();
		} catch (Exception e) {
			log.severe(e.getLocalizedMessage());
			e.printStackTrace();
			responseObserver.onError(Status.INTERNAL
				.withDescription(e.getLocalizedMessage())
				.withCause(e)
				.asRuntimeException()
			);
		}
	}

	/**
	 * Get Attachment related to entity
	 * @param request
	 * @return
	 */
	private Attachment.Builder getAttachmentFromEntity(GetAttachmentRequest request) {
		// validate and get table
		MTable table = validateAndGetTable(request.getTableName());

		int recordId = request.getRecordId();
		if (recordId <= 0 && !Util.isEmpty(request.getRecordUuid(), true)) {
			recordId = RecordUtil.getIdFromUuid(table.getTableName(), request.getRecordUuid(), null);
		}
		if (!RecordUtil.isValidId(recordId, table.getAccessLevel())) {
			return Attachment.newBuilder();
		}

		MAttachment attachment = MAttachment.get(Env.getCtx(), table.getAD_Table_ID(), recordId);
		return convertAttachment(attachment);
	}


	/**
	 * Convert resource
	 * @param reference
	 * @return
	 */
	public static ResourceReference.Builder convertResourceReference(MADAttachmentReference reference) {
		if (reference == null) {
			return ResourceReference.newBuilder();
		}
		return ResourceReference.newBuilder()
			.setId(reference.getAD_AttachmentReference_ID())
			.setUuid(
				ValueUtil.validateNull(reference.getUUID())
			)
			.setName(
				ValueUtil.validateNull(reference.getFileName())
			)
			.setFileName(
				ValueUtil.validateNull(reference.getValidFileName())
			)
			.setDescription(
				ValueUtil.validateNull(reference.getDescription())
			)
			.setTextMessage(
				ValueUtil.validateNull(reference.getTextMsg())
			)
			.setContentType(
				ValueUtil.validateNull(
					MimeType.getMimeType(reference.getFileName())
				)
			)
			.setFileSize(ValueUtil.getDecimalFromBigDecimal(
				reference.getFileSize())
			)
			.setCreated(
				ValueUtil.getLongFromTimestamp(reference.getCreated())
			)
			.setUpdated(
				ValueUtil.getLongFromTimestamp(reference.getUpdated())
			)
		;
	}

	/**
	 * Convert Attachment to gRPC
	 * @param attachment
	 * @return
	 */
	public static Attachment.Builder convertAttachment(MAttachment attachment) {
		if (attachment == null) {
			return Attachment.newBuilder();
		}
		Attachment.Builder builder = Attachment.newBuilder()
			.setId(attachment.getAD_Attachment_ID())
			.setUuid(
				ValueUtil.validateNull(attachment.getUUID())
			)
			.setTitle(ValueUtil.validateNull(attachment.getTitle()))
			.setTextMessage(
				ValueUtil.validateNull(attachment.getTextMsg())
			)
		;

		// validate client info with configured file handler
		MClientInfo clientInfo = MClientInfo.get(attachment.getCtx());
		if (clientInfo == null || clientInfo.getAD_Client_ID() < 0 || clientInfo.getFileHandler_ID() <= 0) {
			return builder;
		}

		MADAttachmentReference.resetAttachmentCacheFromId(clientInfo.getFileHandler_ID(), attachment.getAD_Attachment_ID());
		MADAttachmentReference.getListByAttachmentId(
			attachment.getCtx(),
			clientInfo.getFileHandler_ID(),
			attachment.getAD_Attachment_ID(),
			attachment.get_TrxName()
		)
		.forEach(attachmentReference -> {
			ResourceReference.Builder builderReference = convertResourceReference(attachmentReference);
			builder.addResourceReferences(builderReference);
		});
		return builder;
	}


	@Override
	public void setResourceReference(SetResourceReferenceRequest request, StreamObserver<ResourceReference> responseObserver) {
		try {
			if (request == null) {
				throw new AdempiereException("Object Request Null");
			}
			ResourceReference.Builder resourceReference = setResourceReference(request);
			responseObserver.onNext(resourceReference.build());
			responseObserver.onCompleted();
		} catch (Exception e) {
			log.severe(e.getLocalizedMessage());
			e.printStackTrace();
			responseObserver.onError(Status.INTERNAL
				.withDescription(e.getLocalizedMessage())
				.withCause(e)
				.asRuntimeException()
			);
		}
	}
	
	private ResourceReference.Builder setResourceReference(SetResourceReferenceRequest request) {
		// validate and get client info with configured file handler
		MClientInfo clientInfo = validateAndGetClientInfo();

		// validate file name
		final String fileName = request.getFileName();
		if (Util.isEmpty(fileName, true)) {
			throw new AdempiereException("@Name@ @Attachment@ @NotFound@");
		}
		if (!MimeType.isValidMimeType(fileName)) {
			throw new AdempiereException("@Error@ @FileInvalidExtension@");
		}

		// validate and get table
		MTable table = validateAndGetTable(request.getTableName());

		// validate record
		int recordId = request.getRecordId();
		if (recordId <= 0 && !Util.isEmpty(request.getRecordUuid(), true)) {
			recordId = RecordUtil.getIdFromUuid(request.getTableName(), request.getRecordUuid(), null);
		}
		if (!RecordUtil.isValidId(recordId, table.getAccessLevel())) {
			throw new AdempiereException("@Record_ID@ / @UUID@ @NotFound@");
		}
		final int recordIdentifier = recordId;

		AtomicReference<MADAttachmentReference> attachmentReferenceAtomic = new AtomicReference<MADAttachmentReference>();
		Trx.run(transactionName -> {
			MAttachment attachment = new MAttachment(Env.getCtx(), table.getAD_Table_ID(), recordIdentifier, transactionName);
			if (attachment.getAD_Attachment_ID() <= 0) {
				/**
				 * TODO: `IsDirectLoad` disables `ModelValidator`, `beforeSave` and the `MAttachment.afterSave`
				 * which calls the `MAttachment.saveLOBData` method but generates an error
				 * (Null Pointer Exception) since `items` is initialized to null, when it should
				 * be initialized with a `new ArrayList<MAttachmentEntry>()`.
				 */
				attachment.setIsDirectLoad(true); 
				attachment.saveEx();
			}
			MADAttachmentReference attachmentReference = new MADAttachmentReference(Env.getCtx(), 0, transactionName);
			attachmentReference.setFileHandler_ID(clientInfo.getFileHandler_ID());
			attachmentReference.setAD_Attachment_ID(attachment.getAD_Attachment_ID());
			attachmentReference.setDescription(request.getDescription());
			attachmentReference.setTextMsg(request.getTextMessage());
			attachmentReference.setFileName(fileName);
			// attachmentReference.setFileSize(
			// 	BigDecimal.valueOf(request.getFileSize())
			// );
			attachmentReference.saveEx();
			//	Remove from cache
			MADAttachmentReference.resetAttachmentReferenceCache(clientInfo.getFileHandler_ID(), attachmentReference);
			attachmentReferenceAtomic.set(attachmentReference);
		});

		ResourceReference.Builder builder = convertResourceReference(attachmentReferenceAtomic.get());

		return builder;
	}



	@Override
	public void setResourceReferenceDescription(SetResourceReferenceDescriptionRequest request, StreamObserver<ResourceReference> responseObserver) {
		try {
			if (request == null) {
				throw new AdempiereException("Object Request Null");
			}
			ResourceReference.Builder resourceReference = setResourceReferenceDescription(request);
			responseObserver.onNext(resourceReference.build());
			responseObserver.onCompleted();
		} catch (Exception e) {
			log.severe(e.getLocalizedMessage());
			e.printStackTrace();
			responseObserver.onError(Status.INTERNAL
				.withDescription(e.getLocalizedMessage())
				.withCause(e)
				.asRuntimeException()
			);
		}
	}
	
	ResourceReference.Builder setResourceReferenceDescription(SetResourceReferenceDescriptionRequest request) {
		// validate and get client info with configured file handler
		MClientInfo clientInfo = validateAndGetClientInfo();

		MADAttachmentReference resourceReference = null;
		if (request.getId() > 0) {
			resourceReference = MADAttachmentReference.getById(Env.getCtx(), request.getId(), null);
		}
		if (resourceReference == null && !Util.isEmpty(request.getUuid(), true)) {
			resourceReference = MADAttachmentReference.getByUuid(Env.getCtx(), request.getUuid(), null);
		}
		if (resourceReference == null && !Util.isEmpty(request.getFileName(), true)) {
			resourceReference = MADAttachmentReference.getByUuid(Env.getCtx(), request.getFileName(), null);
		}

		if (resourceReference == null || resourceReference.getAD_AttachmentReference_ID() <= 0) {
			throw new AdempiereException("@AD_AttachmentReference_ID@ @NotFound@");
		}
		
		resourceReference.setDescription(request.getDescription());
		resourceReference.setTextMsg(request.getTextMessage());
		resourceReference.saveEx();

		// reset cache
		MADAttachmentReference.resetAttachmentReferenceCache(clientInfo.getFileHandler_ID(), resourceReference);

		ResourceReference.Builder builder = convertResourceReference(resourceReference);

		return builder;
	}



	@Override
	public void deleteResourceReference(DeleteResourceReferenceRequest request, StreamObserver<Empty> responseObserver) {
		try {
			if (request == null) {
				throw new AdempiereException("Object Request Null");
			}
			Empty.Builder resourceReference = deleteResourceReference(request);
			responseObserver.onNext(resourceReference.build());
			responseObserver.onCompleted();
		} catch (Exception e) {
			log.severe(e.getLocalizedMessage());
			e.printStackTrace();
			responseObserver.onError(Status.INTERNAL
				.withDescription(e.getLocalizedMessage())
				.withCause(e)
				.asRuntimeException()
			);
		}
	}
	
	Empty.Builder deleteResourceReference(DeleteResourceReferenceRequest request) throws Exception {
		String resourceUuid = request.getResourceUuid();
		if (Util.isEmpty(resourceUuid, true)) {
			resourceUuid = getResourceUuidFromName(request.getResourceName());
		}
		if (Util.isEmpty(resourceUuid, true)) {
			throw new AdempiereException("@AD_AttachmentReference_ID@ @NotFound@");
		}

		MADAttachmentReference resourceReference = null;
		if (request.getResourceId() > 0) {
			resourceReference = MADAttachmentReference.getById(Env.getCtx(), request.getResourceId(), null);
		}
		if (resourceReference == null && !Util.isEmpty(request.getResourceUuid(), true)) {
			resourceReference = MADAttachmentReference.getByUuid(Env.getCtx(), request.getResourceUuid(), null);
		}
		if (resourceReference == null && !Util.isEmpty(request.getResourceName(), true)) {
			resourceReference = MADAttachmentReference.getByUuid(Env.getCtx(), request.getResourceName(), null);
		}

		if (resourceReference == null || resourceReference.getAD_AttachmentReference_ID() <= 0) {
			throw new AdempiereException("@AD_AttachmentReference_ID@ @NotFound@");
		}

		// validate and get client info with configured file handler
		MClientInfo clientInfo = validateAndGetClientInfo();

		// delete file on cloud (s3, nexcloud)
		AttachmentUtil.getInstance()
			.clear()
			.withAttachmentId(resourceReference.getAD_Attachment_ID())
			.withFileName(resourceReference.getFileName())
			.withClientId(clientInfo.getAD_Client_ID())
			.deleteAttachment();

		// reset cache
		MADAttachmentReference.resetAttachmentReferenceCache(clientInfo.getFileHandler_ID(), resourceReference);

		return Empty.newBuilder();
	}

	@Override
	public void existsAttachment(ExistsAttachmentRequest request, StreamObserver<ExistsAttachmentResponse> responseObserver) {
		try {
			if (request == null) {
				throw new AdempiereException("Object Request Null");
			}
			ExistsAttachmentResponse.Builder resourceReference = existsAttachment(request);
			responseObserver.onNext(resourceReference.build());
			responseObserver.onCompleted();
		} catch (Exception e) {
			log.severe(e.getLocalizedMessage());
			e.printStackTrace();
			responseObserver.onError(Status.INTERNAL
				.withDescription(e.getLocalizedMessage())
				.withCause(e)
				.asRuntimeException()
			);
		}
	}

	private ExistsAttachmentResponse.Builder existsAttachment(ExistsAttachmentRequest request) {
		ExistsAttachmentResponse.Builder builder = ExistsAttachmentResponse.newBuilder();

		// validate client info with configured file handler
		MClientInfo clientInfo = MClientInfo.get(Env.getCtx());
		if (clientInfo == null || clientInfo.getAD_Client_ID() < 0 || clientInfo.getFileHandler_ID() <= 0) {
			return builder;
		}

		// validate and get table
		MTable table = validateAndGetTable(request.getTableName());

		// validate record
		int recordId = request.getRecordId();
		if (recordId <= 0 && !Util.isEmpty(request.getRecordUuid(), true)) {
			recordId = RecordUtil.getIdFromUuid(table.getTableName(), request.getRecordUuid(), null);
		}
		if (!RecordUtil.isValidId(recordId, table.getAccessLevel())) {
			throw new AdempiereException("@Record_ID@ / @UUID@ @NotFound@");
		}

		MAttachment attachment = MAttachment.get(Env.getCtx(), table.getAD_Table_ID(), recordId);
		if (attachment == null || attachment.getAD_Attachment_ID() <= 0) {
			// without attachment
			return builder;
		}

		int recordCount = new Query(
				Env.getCtx(),
				I_AD_AttachmentReference.Table_Name,
				"AD_Attachment_ID = ?",
				null
			).setParameters(attachment.getAD_Attachment_ID())
			.count();

		return builder
			.setRecordCount(recordCount);
	}

}
