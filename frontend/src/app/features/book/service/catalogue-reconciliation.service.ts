import {inject, Service} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {QueryClient} from '@tanstack/angular-query-experimental';
import {tap} from 'rxjs/operators';
import {API_CONFIG} from '../../../core/config/api-config';
import {bookQueryKeys} from '../data/book-query-keys';

export interface CatalogueMember {
  bookId: number;
  fileId: number;
  title: string;
  authors: string[];
  storedHash: string | null;
}
export interface CatalogueGroup {
  key: string;
  path: string;
  suggestedTargetBookId: number;
  members: CatalogueMember[];
}
export interface CatalogueScan {
  libraryId: number;
  page: number;
  size: number;
  offset?: number;
  totalGroups: number;
  extraRecords: number;
  groups: CatalogueGroup[];
}
export interface CatalogueFilePlan {
  fileId: number; bookId: number; path: string; bookType: string; sha256: string | null; bytes: number;
  action: 'KEEP' | 'SHARED_REFERENCE' | 'QUARANTINE_COPY'; retainedFileId: number; retainedPath: string;
}
export interface QuarantinedCopy {
  id: string; original_path: string; quarantine_path: string; retained_path: string; file_sha256: string;
  state: string; purge_after: string | null; detail: string | null;
}
export interface CataloguePreview {
  libraryId: number;
  targetBookId: number;
  members: CatalogueMember[];
  path: string;
  fileSha256: string | null;
  fileBytes: number;
  eligible: boolean;
  reasons: string[];
  relatedRecords: Record<string, number>;
  metadataDifferences: Record<string, Record<string, string | null>>;
  token: string;
  files?: CatalogueFilePlan[];
  quarantineRetentionDays?: number;
}
export interface CatalogueReceipt {
  id: string;
  targetBookId: number;
  removedBookIds: number[];
  fileSha256: string;
  completedAt: string;
}
export interface CatalogueReceiptSummary {
  id: string;
  targetBookId: number;
  completedAt: string;
}

@Service()
export class CatalogueReconciliationService {
  private readonly http = inject(HttpClient);
  private readonly queryClient = inject(QueryClient);
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/books/catalogue-reconciliation`;

  scan(libraryId: number, page: number, search = '', offset?: number) {
    return this.http.get<CatalogueScan>(this.url, {params: {libraryId, page, size: 20, search, ...(offset === undefined ? {} : {offset})}});
  }
  preview(libraryId: number, group: CatalogueGroup, targetBookId: number) {
    return this.http.post<CataloguePreview>(`${this.url}/preview`, {
      libraryId, bookIds: group.members.map(member => member.bookId), targetBookId,
    });
  }
  merge(preview: CataloguePreview) {
    return this.http.post<CatalogueReceipt>(`${this.url}/merge`, {
      libraryId: preview.libraryId,
      bookIds: preview.members.map(member => member.bookId),
      targetBookId: preview.targetBookId,
      token: preview.token,
      confirmed: true,
    }).pipe(tap(() => void this.queryClient.invalidateQueries({queryKey: bookQueryKeys.all()})));
  }
  quarantinedFile(id: string) {
    return this.http.get(`${this.url}/quarantine/${encodeURIComponent(id)}/file`, {responseType: 'blob'});
  }
  receipt(id: string) {
    return this.http.get<Record<string, unknown>>(`${this.url}/receipts/${encodeURIComponent(id)}`);
  }
  receipts(libraryId: number) {
    return this.http.get<CatalogueReceiptSummary[]>(`${this.url}/receipts`, {params: {libraryId}});
  }
}
