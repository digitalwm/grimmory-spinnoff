import {inject, Service} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {API_CONFIG} from '../../../core/config/api-config';

export interface LibraryIdentityEvidence { location: string; quote: string; }
export interface LibraryBookIdentityAssessment {
  id: number;
  verdict: 'same_work' | 'different_work' | 'uncertain';
  confidence: number;
  title: string;
  authors: string[];
  evidence: LibraryIdentityEvidence[];
  sampleNote?: string | null;
}
export interface LibraryIdentityAssessment {
  ok: boolean;
  summary: string;
  books: LibraryBookIdentityAssessment[];
}

@Service()
export class LibraryToolsService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/library-tools`;

  assessBookIdentity(bookIds: number[]) {
    return this.http.post<LibraryIdentityAssessment>(`${this.url}/identity-assessment`, {bookIds});
  }
}
