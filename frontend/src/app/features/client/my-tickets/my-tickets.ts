
import { Component, inject, OnInit, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';

interface Ticket {
  id: string;
  bookingId: number;
  lineId: string;
  seatIndex: number;
  eventId: number;
  status: string;
  issuedAt: string;
  validUntil: string;
  controlCode: string;
}

@Component({
  selector: 'app-my-tickets',
  imports: [RouterLink],
  templateUrl: './my-tickets.html',
  styleUrl: './my-tickets.css'
})
export class MyTickets implements OnInit {
  private readonly http = inject(HttpClient);

  readonly tickets = signal<Ticket[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');

  ngOnInit(): void {
    this.loadTickets();
  }

  loadTickets(): void {
    this.loading.set(true);
    this.error.set('');

    this.http.get<Ticket[]>('/api/tickets/me').subscribe({
      next: (result) => {
        this.tickets.set(result);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(
          'Impossible de charger les tickets. Vérifiez votre connexion.'
        );
        this.loading.set(false);
      }
    });
  }
}
